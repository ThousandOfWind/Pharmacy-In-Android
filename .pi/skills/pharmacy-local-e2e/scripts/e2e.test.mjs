import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { validateConfig, parseRunArgs, assertResetConsent, establishLaunchOwnership, stopOwnedEmulator, assertDeviceIdentity, compactHierarchy } from './e2e.mjs';

const valid = { javaHome: 'C:/trusted/jdk', androidSdk: 'C:/trusted/sdk', maestroHome: 'C:/trusted/maestro', avdName: 'Pharmacy_E2E_Comparison_API34', port: 5556 };
test('only explicitly named dedicated emulator and local absolute paths are accepted', () => {
  assert.doesNotThrow(() => validateConfig(valid));
  for (const patch of [{ avdName: 'Pharmacy_Local_API34' }, { port: 5557 }, { port: 7000 }, { maestroHome: './maestro' }]) {
    assert.throws(() => validateConfig({ ...valid, ...patch }));
  }
});
test('destructive flow execution requires a separate per-invocation opt-in', () => {
  assert.throws(() => assertResetConsent(parseRunArgs([])));
  assert.throws(() => assertResetConsent(parseRunArgs(['--flow', 'smoke.yaml'])));
  assert.throws(() => assertResetConsent({ allowTestDataReset: 'true' }));
  for (const args of [
    ['--allow-test-data-reset'],
    ['--allow-test-data-reset', '--flow', 'smoke.yaml'],
    ['--flow', 'smoke.yaml', '--allow-test-data-reset'],
  ]) assert.doesNotThrow(() => assertResetConsent(parseRunArgs(args)));
  for (const args of [['--flow'], ['--flow', '--allow-test-data-reset'], ['--allow-test-data-reset', '--allow-test-data-reset'], ['--unknown']]) {
    assert.throws(() => parseRunArgs(args));
  }
});
test('smoke YAML itself rejects missing reset opt-in before any app launch', () => {
  const flow = fs.readFileSync(new URL('../flows/smoke.yaml', import.meta.url), 'utf8');
  const expression = flow.match(/^- assertTrue: \$\{(.+)\}$/m)?.[1];
  assert.ok(expression);
  assert.ok(flow.indexOf('- assertTrue:') < flow.indexOf('- launchApp:'));
  assert.equal(vm.runInNewContext(expression, {}), false);
  assert.equal(vm.runInNewContext(expression, { PHARMACY_ALLOW_TEST_DATA_RESET: true }), false);
  assert.equal(vm.runInNewContext(expression, { PHARMACY_ALLOW_TEST_DATA_RESET: 'true' }), true);
});
test('identity checks fail closed instead of accepting partial names or error output', () => {
  assert.doesNotThrow(() => assertDeviceIdentity('Pharmacy_E2E_Comparison_API34\r\r\nOK\r\n', valid.avdName));
  for (const output of ['Pharmacy_Local_API34\nOK', 'Pharmacy_E2E_Comparison_API34_other\nOK', 'error\nPharmacy_E2E_Comparison_API34\nOK']) {
    assert.throws(() => assertDeviceIdentity(output, valid.avdName));
  }
});
test('launch ownership failure rolls back only its captured launch and surfaces cleanup refusal', () => {
  for (const failAt of ['capture', 'persist']) {
    const calls = [];
    assert.throws(() => establishLaunchOwnership({
      captureIdentity: () => { calls.push('capture'); if (failAt === 'capture') throw new Error('probe failed'); return 'creation-time'; },
      persistIdentity: () => { calls.push('persist'); throw new Error('disk write failed'); },
      rollbackLaunch: identity => calls.push(`rollback:${identity ?? 'not-captured'}`),
    }));
    assert.deepEqual(calls, failAt === 'capture' ? ['capture', 'rollback:not-captured'] : ['capture', 'persist', 'rollback:creation-time']);
  }
  let rolledBack = false;
  establishLaunchOwnership({ captureIdentity: () => 'creation-time', persistIdentity: () => {}, rollbackLaunch: () => { rolledBack = true; } });
  assert.equal(rolledBack, false);
  assert.throws(() => establishLaunchOwnership({
    captureIdentity: () => { throw new Error('probe failed'); }, persistIdentity: () => {},
    rollbackLaunch: () => { throw new Error('not owned'); },
  }), /rollback refused or failed.*not owned/);
});
test('owned emulator cleanup handles ADB not-ready without touching unowned processes', () => {
  for (const ready of [true, false]) {
    const calls = [];
    const method = stopOwnedEmulator({
      verifyOwnership: () => calls.push('verify'),
      checkReady: () => { calls.push('ready'); if (!ready) throw new Error('not booted'); },
      killReadyDevice: () => calls.push('adb'),
      terminateOwnedProcess: () => calls.push('owned-process'),
    });
    assert.equal(method, ready ? 'adb' : 'owned-process');
    assert.deepEqual(calls, ['verify', 'ready', method]);
  }
  let touched = false;
  assert.throws(() => stopOwnedEmulator({
    verifyOwnership: () => { throw new Error('PID reused or not owned'); },
    checkReady: () => { touched = true; },
    killReadyDevice: () => { touched = true; },
    terminateOwnedProcess: () => { touched = true; },
  }));
  assert.equal(touched, false);
});
test('compact inspect retains actual text, resource IDs and bounds without empty wrappers', () => {
  const result = compactHierarchy({ roots: [{ attributes: {}, children: [
    { attributes: { text: '药品名称（必填）', bounds: '[1,2][3,4]', class: 'TextView' }, children: [] },
    { attributes: { accessibilityText: 'Don’t allow', 'resource-id': 'permission-deny', bounds: '[5,6][7,8]' } },
  ] }] });
  assert.equal(result.length, 2);
  assert.equal(result[0].text, '药品名称（必填）');
  assert.equal(result[1].id, 'permission-deny');
  assert.equal(compactHierarchy({ attributes: { text: '我的药箱' }, children: [] })[0].text, '我的药箱');
});
