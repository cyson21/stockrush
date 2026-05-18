import test from 'node:test';
import assert from 'node:assert/strict';

import { adminCaptureTargets } from './capture-web-screenshots.mjs';

test('admin screenshot targets cover portfolio operations pages', () => {
  assert.deepEqual(
    adminCaptureTargets.map((target) => target.fileName),
    [
      'admin-dashboard-desktop.png',
      'admin-coupons-desktop.png',
      'admin-fulfillment-desktop.png',
      'admin-outbox-desktop.png',
    ],
  );
  assert.deepEqual(
    adminCaptureTargets.map((target) => target.tabLabel),
    ['Dashboard', 'Coupons', 'Fulfillment', 'Outbox'],
  );
});
