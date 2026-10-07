function startsRaining(before, after) {
  return before === false && after === true;
}

// Keep in sync with HEAVY_RAIN_LPM in WeatherRepository.kt.
const HEAVY_RAIN_LPM = 0.5;

function rainLevel(flow) {
  if (typeof flow !== 'number' || !Number.isFinite(flow) || flow <= 0) return 'NO_DATA';
  return flow >= HEAVY_RAIN_LPM ? 'HEAVY' : 'LIGHT';
}

module.exports = { startsRaining, rainLevel, HEAVY_RAIN_LPM };
