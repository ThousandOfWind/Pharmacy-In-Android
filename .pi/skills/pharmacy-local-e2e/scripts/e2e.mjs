import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import net from 'node:net';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const APP = 'com.thousandofwind.pharmacy';
const VERSION = '2.11.0';
const skillDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const clean = text => text.replace(/([?&](?:token|access_token|code)=)[^\s&]+/gi, '$1<redacted>')
  .replace(/(authorization\s*[:=]\s*)(?:Bearer\s+)?[^\r\n]+/gi, '$1<redacted>');

export function validateConfig(config) {
  if (!/^Pharmacy_E2E_[A-Za-z0-9_-]+$/.test(config.avdName ?? '')) throw new Error('avdName must name an authorized Pharmacy_E2E_* test AVD.');
  if (!Number.isInteger(config.port) || config.port < 5554 || config.port > 5682 || config.port % 2) throw new Error('port must be an even emulator port between 5554 and 5682.');
  for (const key of ['javaHome', 'androidSdk', 'maestroHome']) {
    if (typeof config[key] !== 'string' || !path.isAbsolute(config[key])) throw new Error(`${key} must be an absolute local path.`);
  }
}

export function parseRunArgs(args) {
  const options = { allowTestDataReset: false, flow: undefined };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--allow-test-data-reset' && !options.allowTestDataReset) {
      options.allowTestDataReset = true;
    } else if (args[i] === '--flow' && options.flow === undefined && args[i + 1] && !args[i + 1].startsWith('--')) {
      options.flow = args[++i];
    } else throw new Error('Unexpected or duplicate run arguments.');
  }
  return options;
}

export function assertResetConsent(options) {
  if (options.allowTestDataReset !== true) throw new Error('Maestro flows may clear the test App database. Stop and obtain explicit user approval for the disposable AVD, then pass --allow-test-data-reset for this run.');
}

export function establishLaunchOwnership({ captureIdentity, persistIdentity, rollbackLaunch }) {
  let identity;
  try { identity = captureIdentity(); persistIdentity(identity); }
  catch (error) {
    try { rollbackLaunch(identity); }
    catch (cleanupError) { throw new Error(`${error.message}; launch rollback refused or failed: ${cleanupError.message}. Stop and inspect the owned launch manually.`); }
    throw error;
  }
}

export function stopOwnedEmulator({ verifyOwnership, checkReady, killReadyDevice, terminateOwnedProcess }) {
  verifyOwnership();
  let ready = false;
  try { checkReady(); ready = true; } catch {}
  if (ready) killReadyDevice();
  else terminateOwnedProcess();
  return ready ? 'adb' : 'owned-process';
}

export function assertDeviceIdentity(output, expected) {
  const lines = output.split(/\r?\n/).map(s => s.trim()).filter(s => s && s !== 'OK');
  if (lines.length !== 1 || lines[0] !== expected) throw new Error('Device identity mismatch; refusing to install, reset or operate this device.');
}

export function compactHierarchy(tree) {
  const items = [];
  function walk(node) {
    if (!node || typeof node !== 'object') return;
    const a = node.attributes ?? {};
    const text = a.text || a.accessibilityText || a.hintText;
    if (text || a['resource-id']) items.push({ text, id: a['resource-id'] || undefined, bounds: a.bounds, class: a.class });
    for (const child of node.children ?? []) walk(child);
  }
  for (const root of tree.roots ?? [tree]) walk(root);
  return items;
}

function execute(command, args, env, { cwd, timeout = 120000, log, quiet = false } = {}) {
  const r = spawnSync(command, args, { env, cwd, timeout, windowsHide: true, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  const stdout = clean(r.stdout ?? ''); const stderr = clean(r.stderr ?? '');
  if (log) fs.writeFileSync(log, `${stdout}\n${stderr}`);
  if (!quiet) { if (stdout.trim()) console.log(stdout.trim()); if (stderr.trim()) console.error(stderr.trim()); }
  if (r.error || r.status !== 0) throw new Error(`${path.basename(command)} failed (${r.status ?? r.error?.code}): ${stderr.slice(-2000) || stdout.slice(-2000)}`);
  return stdout;
}

function loadContext() {
  if (process.platform !== 'win32') throw new Error('This reviewed runner currently supports Windows only.');
  const root = execute('git', ['rev-parse', '--show-toplevel'], process.env, { quiet: true }).trim();
  const repo = path.resolve(root);
  if (!fs.existsSync(path.join(repo, 'app/src/main/java/com/thousandofwind/pharmacy/MainActivity.kt'))) throw new Error('Run from a Pharmacy-In-Android worktree.');
  const file = path.join(repo, '.pi/pharmacy-e2e.local.json');
  if (!fs.existsSync(file)) throw new Error(`Missing ${file}; see references/local-testing.md. No tools will be installed automatically.`);
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const config = { ...raw, javaHome: raw.javaHome ?? process.env.JAVA_HOME, androidSdk: raw.androidSdk ?? process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT };
  validateConfig(config);
  const java = path.join(config.javaHome, 'bin/java.exe');
  const adb = path.join(config.androidSdk, 'platform-tools/adb.exe');
  const emulator = path.join(config.androidSdk, 'emulator/emulator.exe');
  for (const file of [java, adb, emulator, path.join(config.maestroHome, `lib/maestro-cli-${VERSION}.jar`)]) {
    if (!fs.existsSync(file)) throw new Error(`Required trusted tool missing: ${file}`);
  }
  const env = { ...process.env, JAVA_HOME: config.javaHome, ANDROID_HOME: config.androidSdk, ANDROID_SDK_ROOT: config.androidSdk,
    MAESTRO_CLI_NO_ANALYTICS: '1', MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED: 'true' };
  for (const key of Object.keys(env)) if (key.toLowerCase() === 'path') env[key] = `${config.javaHome}/bin;${config.androidSdk}/platform-tools;${env[key]}`;
  const state = path.join(repo, 'build/local-e2e/state'); fs.mkdirSync(state, { recursive: true });
  env.XDG_CONFIG_HOME = path.join(state, 'config'); env.XDG_STATE_HOME = path.join(state, 'state');
  const serial = `emulator-${config.port}`;
  const adbCall = (args, options = {}) => execute(adb, ['-s', serial, ...args], env, { cwd: repo, ...options });
  const maestro = (args, options = {}) => execute(java, ['--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8', `-Duser.home=${state}`,
    '-classpath', path.join(config.maestroHome, 'lib/*'), 'maestro.cli.AppKt', ...args], env, { cwd: repo, ...options });
  return { repo, config, env, state, serial, adb, emulator, java, adbCall, maestro };
}

function deviceReady(c) {
  const state = c.adbCall(['get-state'], { quiet: true }).trim();
  if (state !== 'device') throw new Error('Dedicated emulator is unavailable or not authorized.');
  assertDeviceIdentity(c.adbCall(['emu', 'avd', 'name'], { quiet: true }), c.config.avdName);
  if (c.adbCall(['shell', 'getprop', 'sys.boot_completed'], { quiet: true }).trim() !== '1') throw new Error('Dedicated emulator has not finished booting.');
}
function inventory(c) { return execute(c.adb, ['devices'], c.env, { quiet: true, cwd: c.repo }); }
function checkTools(c) {
  const version = c.maestro(['--version'], { quiet: true });
  if (!version.split(/\r?\n/).some(s => s.trim() === VERSION)) throw new Error(`Expected Maestro ${VERSION}; stop and review installation.`);
  const avds = execute(c.emulator, ['-list-avds'], c.env, { quiet: true }).split(/\r?\n/).map(s => s.trim());
  if (!avds.includes(c.config.avdName)) throw new Error('Approved dedicated AVD does not exist; ask the user to provision it.');
}
export function reservationPaths(root, config) {
  return [path.join(root, `avd-${config.avdName}.json`), path.join(root, `port-${config.port}.json`)];
}

export function reserveDevice(root, owner) {
  fs.mkdirSync(root, { recursive: true });
  const created = [];
  try {
    for (const file of reservationPaths(root, owner)) {
      const fd = fs.openSync(file, 'wx'); created.push(file);
      try { fs.writeFileSync(fd, JSON.stringify(owner)); } finally { fs.closeSync(fd); }
    }
  } catch (error) {
    created.forEach(file => fs.unlinkSync(file));
    if (error.code !== 'EEXIST') throw error;
    throw new Error('AVD or port is reserved by another task. Use a separately authorized AVD and unused port; do not remove its reservation.');
  }
}

export function verifyReservation(root, owner) {
  for (const file of reservationPaths(root, owner)) {
    const actual = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!owner.token || actual.token !== owner.token || actual.repo !== owner.repo || actual.avdName !== owner.avdName || actual.port !== owner.port)
      throw new Error('Task device reservation mismatch; refusing device operation.');
  }
}

export function releaseReservation(root, owner) {
  verifyReservation(root, owner);
  reservationPaths(root, owner).forEach(file => fs.unlinkSync(file));
}

const reservationRoot = () => path.join(os.tmpdir(), 'pharmacy-local-e2e-reservations');
function launchOwner(c) {
  const owner = JSON.parse(fs.readFileSync(path.join(c.state, 'emulator-started.json'), 'utf8'));
  if (owner.avd !== c.config.avdName || owner.serial !== c.serial || owner.repo !== c.repo || !Number.isSafeInteger(owner.pid) || owner.pid <= 0 || !/^[0-9T:.+-]+Z$/.test(owner.creationTime ?? '')) throw new Error('Emulator launch ownership mismatch.');
  return owner;
}

function launchIdentityProbe(c, owner, requireAlive = true) {
  const matching = `$p.Name -eq 'emulator.exe' -and $p.CreationDate.ToUniversalTime().ToString('o') -eq '${owner.creationTime}' -and $p.CommandLine -match '-avd ${c.config.avdName}(?: |$)' -and $p.CommandLine -match '-port ${c.config.port}(?: |$)'`;
  const probe = `$p = Get-CimInstance Win32_Process -Filter 'ProcessId = ${owner.pid}'; if (-not $p) { ${requireAlive ? 'exit 1' : "'exited'; exit 0"} }; if (-not (${matching})) { exit 1 }; 'alive'`;
  return execute('powershell.exe', ['-NoProfile', '-Command', probe], c.env, { quiet: true }).trim();
}

async function confirmLaunchExit(c, owner) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const exited = launchIdentityProbe(c, owner, false) === 'exited';
    const connected = inventory(c).split(/\r?\n/).some(line => line.startsWith(`${c.serial}\t`));
    if (exited && !connected) { await assertPortsFree(c.config.port); return; }
    await sleep(500);
  }
  throw new Error('Owned emulator exit is not confirmed; reservation retained. Do not start another task on this AVD/port.');
}

function reservedOwner(c, requireAlive = true) {
  const file = path.join(c.state, 'device-reservation.json');
  if (!fs.existsSync(file)) throw new Error('Run start for this task first; never attach to another task\'s running emulator.');
  const owner = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (owner.repo !== c.repo || owner.avdName !== c.config.avdName || owner.port !== c.config.port) throw new Error('Local task reservation does not match current configuration.');
  verifyReservation(reservationRoot(), owner);
  launchIdentityProbe(c, launchOwner(c), requireAlive);
  return owner;
}

async function assertPortsFree(port) {
  for (const number of [port, port + 1]) for (const host of ['127.0.0.1', '::1']) {
    const occupied = await new Promise((resolve, reject) => {
      const socket = net.connect({ host, port: number });
      socket.once('connect', () => { socket.destroy(); resolve(true); });
      socket.once('error', error => { socket.destroy(); if (['ECONNREFUSED', 'ENETUNREACH', 'EHOSTUNREACH'].includes(error.code)) resolve(false); else reject(error); });
      socket.setTimeout(1500, () => { socket.destroy(); reject(new Error('Cannot prove emulator ports are free.')); });
    });
    if (occupied) throw new Error(`Port ${number} is occupied. Use an unused even port pair; do not stop the occupant.`);
  }
}

async function withDeviceLock(c, operation) {
  const lockDir = path.join(os.tmpdir(), 'pharmacy-local-e2e-locks'); fs.mkdirSync(lockDir, { recursive: true });
  const file = path.join(lockDir, `${c.serial}.lock`);
  let fd;
  try { fd = fs.openSync(file, 'wx'); } catch (e) {
    if (e.code === 'EEXIST') throw new Error(`Device busy or previous runner stopped unexpectedly. Review ${file} before manual cleanup; never delete an active lock.`);
    throw e;
  }
  try { fs.writeFileSync(fd, JSON.stringify({ pid: process.pid, repo: c.repo, avd: c.config.avdName })); return await operation(); }
  finally { fs.closeSync(fd); fs.unlinkSync(file); }
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function outputDir(c) {
  const name = `${new Date().toISOString().replace(/[:.]/g, '-')}-${process.pid}`;
  const dir = path.join(c.repo, 'build/local-e2e', name); fs.mkdirSync(dir, { recursive: true }); return dir;
}
function resolveFlow(c, input) {
  const file = input ? path.resolve(input) : path.join(skillDir, 'flows/smoke.yaml');
  const real = fs.realpathSync(file);
  if (![c.repo, skillDir].some(root => { const relative = path.relative(fs.realpathSync(root), real); return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative); })) throw new Error('Flow must be reviewed and stored in this project or skill.');
  const text = fs.readFileSync(real, 'utf8');
  if (!/^appId:\s*com\.thousandofwind\.pharmacy\s*$/m.test(text)) throw new Error('Flow root appId must be the Pharmacy app.');
  return real;
}

async function main() {
  const [action, ...args] = process.argv.slice(2);
  if (!['doctor', 'start', 'run', 'inspect', 'screenshot', 'stop'].includes(action)) throw new Error('Usage: e2e.mjs doctor|start|run --allow-test-data-reset [--flow <path>]|inspect|screenshot|stop');
  const runOptions = action === 'run' ? parseRunArgs(args) : {};
  if (action !== 'run' && args.length) throw new Error('Unexpected arguments.');
  // All flows require opt-in: checking only the root YAML would miss destructive child flows.
  if (action === 'run') assertResetConsent(runOptions);
  const c = loadContext();
  const ownership = path.join(c.state, 'emulator-started.json');
  if (action === 'doctor') {
    checkTools(c);
    const devices = inventory(c);
    const line = devices.split(/\r?\n/).find(s => s.startsWith(`${c.serial}\t`));
    if (line) deviceReady(c);
    console.log(JSON.stringify({ ready: true, repo: c.repo, maestro: VERSION, avd: c.config.avdName, serial: c.serial, emulatorRunning: Boolean(line) }, null, 2)); return;
  }
  await withDeviceLock(c, async () => {
    if (action === 'start') {
      checkTools(c);
      if (inventory(c).split(/\r?\n/).some(s => s.startsWith(`${c.serial}\t`))) {
        reservedOwner(c);
        if (!fs.existsSync(ownership)) throw new Error('This task has no launch ownership; do not reuse the running emulator.');
        deviceReady(c); console.log('This task\'s reserved emulator is already running.'); return;
      }
      if (fs.existsSync(ownership) || fs.existsSync(path.join(c.state, 'device-reservation.json'))) throw new Error('Previous launch/reservation needs inspection; do not relaunch or steal its device.');
      await assertPortsFree(c.config.port);
      const owner = { repo: c.repo, avdName: c.config.avdName, port: c.config.port, token: crypto.randomUUID() };
      reserveDevice(reservationRoot(), owner);
      try { fs.writeFileSync(path.join(c.state, 'device-reservation.json'), JSON.stringify(owner), { flag: 'wx' }); }
      catch (error) { releaseReservation(reservationRoot(), owner); throw error; }
      const launchStarted = new Date().toISOString();
      let stdout, stderr, child, launchError;
      try {
        stdout = fs.openSync(path.join(c.state, 'emulator.stdout.log'), 'a');
        stderr = fs.openSync(path.join(c.state, 'emulator.stderr.log'), 'a');
        child = spawn(c.emulator, ['-avd', c.config.avdName, '-port', String(c.config.port), '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot-save'],
          { env: c.env, detached: true, windowsHide: true, stdio: ['ignore', stdout, stderr] });
        child.on('error', error => { launchError = error; }); child.unref();
      } catch (error) {
        if (!child?.pid) {
          releaseReservation(reservationRoot(), owner);
          fs.unlinkSync(path.join(c.state, 'device-reservation.json'));
        }
        throw error;
      } finally {
        if (stdout !== undefined) fs.closeSync(stdout);
        if (stderr !== undefined) fs.closeSync(stderr);
      }
      const processProbe = `$p = Get-CimInstance Win32_Process -Filter 'ProcessId = ${child.pid}'; if ($p -and $p.Name -eq 'emulator.exe' -and $p.CommandLine -match '-avd ${c.config.avdName}(?: |$)' -and $p.CommandLine -match '-port ${c.config.port}(?: |$)') { $p.CreationDate.ToUniversalTime().ToString('o') } else { exit 1 }`;
      establishLaunchOwnership({
        captureIdentity: () => {
          const creationTime = execute('powershell.exe', ['-NoProfile', '-Command', processProbe], c.env, { quiet: true }).trim();
          if (!/^[0-9T:.+-]+Z$/.test(creationTime)) throw new Error('Cannot establish emulator process ownership.');
          return creationTime;
        },
        persistIdentity: creationTime => fs.writeFileSync(ownership, JSON.stringify({ pid: child.pid, creationTime, avd: c.config.avdName, serial: c.serial, repo: c.repo, started: launchStarted })),
        rollbackLaunch: creationTime => {
          if (!Number.isSafeInteger(child.pid) || child.pid <= 0) {
            releaseReservation(reservationRoot(), owner);
            fs.unlinkSync(path.join(c.state, 'device-reservation.json'));
            return; // No OS process was created.
          }
          const timeCheck = creationTime ? `$p.CreationDate.ToUniversalTime().ToString('o') -eq '${creationTime}'`
            : `$p.CreationDate.ToUniversalTime() -ge [datetime]'${launchStarted}'`;
          const rollback = `$p = Get-CimInstance Win32_Process -Filter 'ProcessId = ${child.pid}'; if (-not $p) { exit 0 }; if (-not ($p.Name -eq 'emulator.exe' -and (${timeCheck}) -and $p.CommandLine -match '-avd ${c.config.avdName}(?: |$)' -and $p.CommandLine -match '-port ${c.config.port}(?: |$)')) { exit 1 }; & taskkill.exe /PID ${child.pid} /T /F; exit $LASTEXITCODE`;
          execute('powershell.exe', ['-NoProfile', '-Command', rollback], c.env, { quiet: true });
          releaseReservation(reservationRoot(), owner);
          fs.unlinkSync(path.join(c.state, 'device-reservation.json'));
        },
      });
      const deadline = Date.now() + 180000;
      while (Date.now() < deadline) { if (launchError) throw launchError; try { deviceReady(c); console.log(`Started dedicated AVD ${c.config.avdName} (${c.serial}).`); return; } catch {} await sleep(2000); }
      throw new Error(`Dedicated emulator did not boot within 180 seconds; review ${c.state}.`);
    }
    if (action === 'stop') {
      const reservation = reservedOwner(c, false);
      if (!fs.existsSync(ownership)) throw new Error('This runner did not start this emulator; leave it running.');
      const owner = JSON.parse(fs.readFileSync(ownership, 'utf8'));
      if (owner.avd !== c.config.avdName || owner.serial !== c.serial || owner.repo !== c.repo || !Number.isSafeInteger(owner.pid) || owner.pid <= 0 || !/^[0-9T:.+-]+Z$/.test(owner.creationTime ?? '')) throw new Error('Emulator ownership mismatch; refusing shutdown.');
      const probe = `$p = Get-CimInstance Win32_Process -Filter 'ProcessId = ${owner.pid}'; if (-not ($p -and $p.Name -eq 'emulator.exe' -and $p.CreationDate.ToUniversalTime().ToString('o') -eq '${owner.creationTime}' -and $p.CommandLine -match '-avd ${c.config.avdName}(?: |$)' -and $p.CommandLine -match '-port ${c.config.port}(?: |$)')) { exit 1 };`;
      const method = launchIdentityProbe(c, owner, false) === 'exited' ? 'already-exited' : stopOwnedEmulator({
        verifyOwnership: () => execute('powershell.exe', ['-NoProfile', '-Command', `${probe} 'owned'`], c.env, { quiet: true }),
        checkReady: () => deviceReady(c),
        killReadyDevice: () => c.adbCall(['emu', 'kill']),
        // Recheck PID, creation time and launch arguments immediately before terminating only this owned process tree.
        terminateOwnedProcess: () => execute('powershell.exe', ['-NoProfile', '-Command', `${probe} & taskkill.exe /PID ${owner.pid} /T /F; exit $LASTEXITCODE`], c.env, { quiet: true }),
      });
      await confirmLaunchExit(c, owner);
      fs.unlinkSync(ownership);
      releaseReservation(reservationRoot(), reservation);
      fs.unlinkSync(path.join(c.state, 'device-reservation.json'));
      console.log(`Dedicated emulator stopped (${method}); task reservation released.`); return;
    }
    reservedOwner(c);
    deviceReady(c);
    const dir = outputDir(c); console.log(`Evidence: ${dir}`);
    if (action === 'screenshot') {
      const r = spawnSync(c.adb, ['-s', c.serial, 'exec-out', 'screencap', '-p'], { env: c.env, timeout: 30000, maxBuffer: 16 * 1024 * 1024, windowsHide: true });
      if (r.status !== 0 || !r.stdout?.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) throw new Error('Screenshot capture failed.');
      const file = path.join(dir, 'screen.png'); fs.writeFileSync(file, r.stdout); console.log(file); return;
    }
    if (action === 'inspect') {
      checkTools(c);
      const raw = c.maestro(['--udid', c.serial, '--no-ansi', 'hierarchy'], { quiet: true, timeout: 90000 });
      const tree = JSON.parse(raw.slice(raw.indexOf('{'))); const compact = compactHierarchy(tree);
      fs.writeFileSync(path.join(dir, 'hierarchy.json'), JSON.stringify(tree, null, 2)); fs.writeFileSync(path.join(dir, 'elements.json'), JSON.stringify(compact, null, 2));
      console.log(JSON.stringify(compact.slice(0, 100), null, 2)); return;
    }
    const result = { status: 'BLOCKED', started: new Date().toISOString(), repo: c.repo, appId: APP, avd: c.config.avdName, serial: c.serial, maestro: VERSION };
    const started = performance.now();
    try {
      const flow = resolveFlow(c, runOptions.flow); checkTools(c); result.flow = flow;
      result.commit = execute('git', ['rev-parse', 'HEAD'], c.env, { cwd: c.repo, quiet: true }).trim();
      result.worktreeStatus = execute('git', ['status', '--short'], c.env, { cwd: c.repo, quiet: true }).trim();
      console.log('Building the current worktree APK…');
      execute('cmd.exe', ['/d', '/c', 'gradlew.bat', '--no-daemon', 'assembleDebug'], c.env,
        { cwd: c.repo, timeout: 600000, log: path.join(dir, 'build.log') });
      const apk = path.join(c.repo, 'app/build/outputs/apk/debug/app-debug.apk');
      result.apkSha256 = crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex');
      reservedOwner(c); deviceReady(c); c.adbCall(['install', '-r', apk], { timeout: 120000, log: path.join(dir, 'install.log') });
      const installed = c.adbCall(['shell', 'pm', 'path', APP], { quiet: true }).split(/\r?\n/).find(s => s.startsWith('package:') && s.endsWith('/base.apk'))?.slice(8);
      if (!installed) throw new Error('Installed APK not found.');
      const hash = c.adbCall(['shell', 'sha256sum', installed], { quiet: true }).trim().split(/\s+/)[0];
      if (hash !== result.apkSha256) throw new Error('Installed APK hash differs from the current build.');
      // Recheck both the resolved device identity and this invocation's explicit consent just before execution.
      reservedOwner(c); deviceReady(c); assertResetConsent(runOptions); result.allowTestDataReset = true; result.status = 'FAILED';
      c.maestro(['--udid', c.serial, '--no-ansi', 'test', '--env', 'PHARMACY_ALLOW_TEST_DATA_RESET=true', '--format', 'JUNIT', '--output', path.join(dir, 'report.xml'),
        '--test-output-dir', path.join(dir, 'maestro'), flow], { timeout: 300000, log: path.join(dir, 'maestro.log') });
      if (!fs.existsSync(path.join(dir, 'report.xml'))) throw new Error('Maestro succeeded without the expected JUnit report.');
      result.status = 'PASSED';
    } catch (error) { result.error = clean(error.message); throw error; }
    finally { result.elapsedMs = Math.round(performance.now() - started); fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify(result, null, 2)); console.log(`Result: ${result.status}; ${path.join(dir, 'result.json')}`); }
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(`BLOCKED/FAILED: ${clean(error.message)}`); process.exitCode = 1; });
