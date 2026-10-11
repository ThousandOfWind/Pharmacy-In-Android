import test from 'node:test';
import assert from 'node:assert/strict';
import { validateConfig, assertDeviceIdentity, compactHierarchy } from './e2e.mjs';

const valid = { javaHome: 'C:/trusted/jdk', androidSdk: 'C:/trusted/sdk', maestroHome: 'C:/trusted/maestro', avdName: 'Pharmacy_E2E_Comparison_API34', port: 5556 };
test('only explicitly named dedicated emulator and local absolute paths are accepted', () => {
  assert.doesNotThrow(() => validateConfig(valid));
  for (const patch of [{ avdName: 'Pharmacy_Local_API34' }, { port: 5557 }, { port: 7000 }, { maestroHome: './maestro' }]) {
    assert.throws(() => validateConfig({ ...valid, ...patch }));
  }
});
test('identity checks fail closed instead of accepting partial names or error output', () => {
  assert.doesNotThrow(() => assertDeviceIdentity('Pharmacy_E2E_Comparison_API34\r\r\nOK\r\n', valid.avdName));
  for (const output of ['Pharmacy_Local_API34\nOK', 'Pharmacy_E2E_Comparison_API34_other\nOK', 'error\nPharmacy_E2E_Comparison_API34\nOK']) {
    assert.throws(() => assertDeviceIdentity(output, valid.avdName));
  }
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
