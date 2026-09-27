'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {verifyImage, verifyBuildVersion} = require('./image.cjs');
const expected = {imageId: `sha256:${'a'.repeat(64)}`, imageDigest: `sha256:${'b'.repeat(64)}`,
  sha: 'c'.repeat(40), version: '0.1.0-SNAPSHOT', pomVersion: '0.1.0-SNAPSHOT'};
const fixture = id => ({Id: id, Os: 'linux', Architecture: 'amd64', Config: {Labels: {
  'org.opencontainers.image.revision': expected.sha,
  'org.opencontainers.image.version': expected.version
}}});

test('accepts the configuration ID reported by traditional Docker stores', () => {
  assert.equal(verifyImage(fixture(expected.imageId), expected), expected.imageId);
});
test('accepts the exported manifest ID reported by containerd image stores', () => {
  assert.equal(verifyImage(fixture(expected.imageDigest), expected), expected.imageDigest);
});
test('rejects a different image even when its version and SHA labels match', () => {
  assert.throws(() => verifyImage(fixture(`sha256:${'d'.repeat(64)}`), expected), /identity/);
});
test('rejects incorrect platform, source, label, or committed Maven version', () => {
  const wrongPlatform = fixture(expected.imageId); wrongPlatform.Architecture = 'arm64';
  const wrongSha = fixture(expected.imageId); wrongSha.Config.Labels['org.opencontainers.image.revision'] = 'd'.repeat(40);
  const wrongVersion = fixture(expected.imageId); wrongVersion.Config.Labels['org.opencontainers.image.version'] = '0.1.0';
  for (const image of [wrongPlatform, wrongSha, wrongVersion]) assert.throws(() => verifyImage(image, expected));
  assert.throws(() => verifyImage(fixture(expected.imageId), {...expected, pomVersion: '0.2.0'}));
});
test('requires the packaged Maven version, not a substring or a Docker tag', () => {
  verifyBuildVersion('build.artifact=banking-api\nbuild.version=0.1.0-SNAPSHOT\n', expected.version);
  assert.throws(() => verifyBuildVersion('build.version=0.1.0\n', expected.version));
  assert.throws(() => verifyBuildVersion('build.version=0.1.0-SNAPSHOT-extra\n', expected.version));
});
