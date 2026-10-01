const { initializeApp } = require('firebase-admin/app');
const { getMessaging } = require('firebase-admin/messaging');
const { onValueUpdated } = require('firebase-functions/v2/database');
const { startsRaining } = require('./rain');
initializeApp();

// Match this region to the Realtime Database location before deployment.
exports.rainAlert = onValueUpdated({
  ref: '/devices/{deviceId}/telemetry/raining', region: 'asia-southeast1', retry: true
}, async event => {
  if (!startsRaining(event.data.before.val(), event.data.after.val())) return;
  // Ignore very old retry events: do not ring hours after the shower ended.
  if (Date.now() - Date.parse(event.time) > 120000) return;
  const root = event.data.after.ref.root;
  const members = (await root.child(`deviceUsers/${event.params.deviceId}`).get()).val() || {};
  const tokenOwners = new Map();
  for (const [uid, allowed] of Object.entries(members)) {
    if (allowed !== true) continue;
    const tokens = (await root.child(`users/${uid}/fcmTokens`).get()).val() || {};
    for (const [token, enabled] of Object.entries(tokens)) {
      if (enabled === true) tokenOwners.set(token, uid);
    }
  }
  const tokens = [...tokenOwners.keys()];
  let transientFailure = false;
  for (let i = 0; i < tokens.length; i += 500) {
    const batch = tokens.slice(i, i + 500);
    const result = await getMessaging().sendEachForMulticast({
      tokens: batch,
      notification: { title: 'ĐANG MƯA', body: `${event.params.deviceId}: Phát hiện mưa. Kiểm tra trạng thái giàn phơi.` },
      data: { deviceId: event.params.deviceId, eventId: event.id },
      android: { priority: 'high', ttl: 120000, notification: {
        channelId: 'rain_alerts', icon: 'ic_rain', tag: 'rain', sound: 'default',
        defaultVibrateTimings: true
      }}
    });
    for (let j = 0; j < result.responses.length; j++) {
      const response = result.responses[j];
      if (response.success) continue;
      const code = response.error?.code;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        await root.child(`users/${tokenOwners.get(batch[j])}/fcmTokens/${batch[j]}`).remove();
      } else transientFailure = true;
    }
  }
  // Delivery is at-least-once; the stable notification tag replaces duplicate banners.
  if (transientFailure) throw new Error('Transient FCM failure; retry within event TTL');
});
