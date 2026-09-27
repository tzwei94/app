'use strict';

function verifyImage(image, expected) {
  const labels = image.Config?.Labels || {};
  const knownIdentity = [expected.imageId, expected.imageDigest]
    .filter(value => /^sha256:[a-f0-9]{64}$/.test(value || ''));
  if (!knownIdentity.includes(image.Id) || image.Os !== 'linux' || image.Architecture !== 'amd64' ||
      labels['org.opencontainers.image.revision'] !== expected.sha ||
      labels['org.opencontainers.image.version'] !== expected.version ||
      expected.pomVersion !== expected.version) {
    throw new Error('Image identity, source SHA, platform or Maven version differs from the tested build.');
  }
  return image.Id;
}

function verifyBuildVersion(properties, version) {
  if (!properties.split(/\r?\n/).includes(`build.version=${version}`)) {
    throw new Error('The application JAR version differs from its image label.');
  }
}

module.exports = {verifyImage, verifyBuildVersion};
