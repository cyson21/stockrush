import assert from 'node:assert/strict';
import test from 'node:test';
import {
  androidAdbCandidates,
  boundsCenter,
  buildSelectorPlan,
  findNode,
  parseNodes,
  redactValue,
} from './android-uiautomator-smoke.mjs';

const xml = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="" resource-id="mobile-product-list-screen" class="android.view.ViewGroup" content-desc="" enabled="true" clickable="false" bounds="[0,63][1080,2337]" />
  <node text="로그인됨" resource-id="mobile-auth-status-label" class="android.widget.TextView" content-desc="" enabled="true" clickable="false" bounds="[42,420][400,490]" />
  <node text="Limited Hoodie" resource-id="mobile-product-card-LIMITED-001" class="android.widget.Button" content-desc="상품 선택 LIMITED-001" enabled="true" clickable="true" bounds="[40,900][1040,1090]" />
  <node text="LIMITED-001-S" resource-id="mobile-stock-row-LIMITED-001-S" class="android.widget.Button" content-desc="SKU 선택 LIMITED-001-S" enabled="true" clickable="true" bounds="[40,1120][1040,1310]" />
</hierarchy>`;

test('parseNodes reads UIAutomator node attributes safely', () => {
  const nodes = parseNodes(xml);

  assert.equal(nodes.length, 4);
  assert.equal(nodes[2].resourceId, 'mobile-product-card-LIMITED-001');
  assert.equal(nodes[2].contentDesc, '상품 선택 LIMITED-001');
  assert.equal(nodes[2].clickable, true);
  assert.equal(nodes[2].enabled, true);
});

test('findNode locates nodes by resource id, text, and content description', () => {
  assert.equal(findNode(xml, { resourceId: 'mobile-auth-status-label', text: '로그인됨' })?.text, '로그인됨');
  assert.equal(findNode(xml, { contentDesc: '상품 선택 LIMITED-001' })?.resourceId, 'mobile-product-card-LIMITED-001');
  assert.equal(findNode(xml, { resourceId: 'missing' }), null);
});

test('boundsCenter returns tap coordinates for Android bounds', () => {
  assert.deepEqual(boundsCenter('[40,900][1040,1090]'), { x: 540, y: 995 });
});

test('buildSelectorPlan supports optional coupon entry without leaking secrets', () => {
  const plan = buildSelectorPlan({ couponCode: 'WELCOME10', paymentMethod: 'CARD' });

  assert.deepEqual(
    plan.map((step) => step.id),
    [
      'screen-loaded',
      'auth-ready',
      'select-product',
      'select-sku',
      'set-quantity',
      'set-coupon',
      'apply-coupon',
      'select-payment',
      'submit-order',
      'created-order',
      'terminal-saga-status',
    ],
  );
  assert.equal(plan.find((step) => step.id === 'set-coupon')?.value, 'WELCOME10');
});

test('redactValue masks sensitive command values in evidence', () => {
  assert.equal(redactValue('customer@example.com', true), '<redacted>');
  assert.equal(redactValue('WELCOME10', false), 'WELCOME10');
});

test('androidAdbCandidates includes PATH and configured SDK platform-tools', () => {
  const candidates = androidAdbCandidates({
    ANDROID_HOME: '/opt/android-home',
    ANDROID_SDK_ROOT: '/opt/android-sdk',
    LOCALAPPDATA: 'C:\\Users\\demo\\AppData\\Local',
  }, 'linux', '/Users/demo');

  assert.equal(candidates[0], 'adb');
  assert.ok(candidates.includes('/opt/android-home/platform-tools/adb'));
  assert.ok(candidates.includes('/opt/android-sdk/platform-tools/adb'));
  assert.ok(candidates.includes('/Users/demo/Library/Android/sdk/platform-tools/adb'));
});
