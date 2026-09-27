'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {deploymentSummary} = require('./image.cjs');
const manifest = {image: `123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/banking-dev/banking-api@sha256:${'a'.repeat(64)}`,
  source_sha: 'b'.repeat(40), version: '0.1.0', repository: 'example/app', run_id: 42, run_attempt: 2};

test('handoff uses the selected manifest SHA, not the workflow or current main SHA', () => {
  const summary = deploymentSummary(manifest, {deploymentRepository: 'example/infra', manifestUrl: 'https://github.com/example/app/actions/runs/42/artifacts/9'});
  const inputs = JSON.parse(/```json\n([\s\S]*?)\n```/.exec(summary)[1]);
  assert.equal(inputs.image, manifest.image);
  assert.equal(inputs.source_sha, 'b'.repeat(40));
  assert.equal(inputs.action, 'deploy');
  assert.equal(inputs.first_release, false);
  assert.match(inputs.alloy_image, /Publish Alloy/);
  assert.match(summary, /https:\/\/github.com\/example\/infra\/actions\/workflows\/deploy-dev.yml/);
  assert.match(summary, /https:\/\/github.com\/example\/infra\/actions\/workflows\/publish-alloy.yml/);
  assert.match(summary, /--repo example\/infra/);
  assert.match(summary, /actions\/runs\/42\/attempts\/2/);
});

test('handoff rejects invalid repository names before generating a shell command', () => {
  assert.throws(() => deploymentSummary(manifest, {deploymentRepository: 'bad/repo; echo nope'}), /repository/i);
});
