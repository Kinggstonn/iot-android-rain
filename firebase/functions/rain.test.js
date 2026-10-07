const { test } = require('node:test');
const assert = require('node:assert/strict');
const { startsRaining, rainLevel } = require('./rain');
test('Only dry-to-rain transitions alert, never initialization or repeated rain', () => {
  assert.equal(startsRaining(false, true), true);
  for (const pair of [[null,true],[undefined,true],[true,true],[true,false],[false,false],[false,null],['false',true]]) {
    assert.equal(startsRaining(...pair), false);
  }
});
test('Rain level follows flow thresholds', () => {
  assert.equal(rainLevel(undefined), 'NO_DATA');
  assert.equal(rainLevel(null), 'NO_DATA');
  assert.equal(rainLevel(NaN), 'NO_DATA');
  assert.equal(rainLevel('0.5'), 'NO_DATA');
  assert.equal(rainLevel(0), 'NO_DATA');
  assert.equal(rainLevel(-0.1), 'NO_DATA');
  assert.equal(rainLevel(0.001), 'LIGHT');
  assert.equal(rainLevel(0.499), 'LIGHT');
  assert.equal(rainLevel(0.5), 'HEAVY');
  assert.equal(rainLevel(4.2), 'HEAVY');
});
