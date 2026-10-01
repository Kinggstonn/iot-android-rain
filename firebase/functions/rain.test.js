const { test } = require('node:test');
const assert = require('node:assert/strict');
const { startsRaining } = require('./rain');
test('Only dry-to-rain transitions alert, never initialization or repeated rain', () => {
  assert.equal(startsRaining(false, true), true);
  for (const pair of [[null,true],[undefined,true],[true,true],[true,false],[false,false],[false,null],['false',true]]) {
    assert.equal(startsRaining(...pair), false);
  }
});
