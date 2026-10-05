'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {deploymentSummary} = require('./image.cjs');
const manifest = {image: `123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/banking-dev/banking-api@sha256:${'a'.repeat(64)}`,
  source_sha: 'b'.repeat(40), version: '0.1.0', repository: 'example/app', run_id: 42, run_attempt: 2};

test('handoff uses the selected manifest SHA, not the workflow or current main SHA', () => {
  const summary = deploymentSummary(manifest, {deploymentRepository: 'example/infra', manifestUrl: 'https://github.com/example/app/actions/runs/42/artifacts/9'});
  const rows = [...summary.matchAll(/^\| `([^`]+)` \| (.*) \|$/gm)];
  assert.deepEqual(rows.map(row => row[1]), ['image', 'alloy_image', 'source_sha', 'action', 'first_release']);
  const inputs = Object.fromEntries(rows.map(row => [row[1], row[2]]));
  assert.equal(inputs.image, `\`${manifest.image}\``);
  assert.equal(inputs.source_sha, `\`${manifest.source_sha}\``);
  assert.equal(inputs.action, '`deploy`');
  assert.match(inputs.first_release, /`false`.*unchecked/);
  assert.match(inputs.alloy_image, /existing validated Alloy digest/);
  assert.match(inputs.alloy_image, /Publish Alloy Image/);
  assert.match(summary, /https:\/\/github.com\/example\/infra\/actions\/workflows\/deploy-dev.yml/);
  assert.match(summary, /https:\/\/github.com\/example\/infra\/actions\/workflows\/publish-alloy.yml/);
  assert.match(summary, /--repo example\/infra/);
  assert.match(summary, /actions\/runs\/42\/attempts\/2/);
});

test('handoff rejects invalid repository names before generating a shell command', () => {
  assert.throws(() => deploymentSummary(manifest, {deploymentRepository: 'bad/repo; echo nope'}), /repository/i);
});

test('handoff defaults to the banking infrastructure repository owned by the application owner', () => {
  const summary = deploymentSummary({...manifest, repository: 'example/banking-api'});
  assert.match(summary, /https:\/\/github.com\/example\/banking-infrastructure\/actions\/workflows\/deploy-dev.yml/);
  assert.match(summary, /--repo example\/banking-infrastructure/);
});
