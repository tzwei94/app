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

function deploymentSummary(manifest, {deploymentRepository, manifestUrl, serverUrl = 'https://github.com'} = {}) {
  const repository = deploymentRepository || `${manifest.repository.split('/')[0]}/banking-infrastructure`;
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
    throw new Error('Invalid deployment repository; use OWNER/REPOSITORY.');
  }
  if (!/^[a-f0-9]{40}$/.test(manifest.source_sha) ||
      !/^\d{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com\/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$/.test(manifest.image)) {
    throw new Error('Deployment inputs must contain the published ECR digest and full application source SHA.');
  }
  const base = `${serverUrl}/${repository}/actions/workflows`;
  const ci = `${serverUrl}/${manifest.repository}/actions/runs/${manifest.run_id}/attempts/${manifest.run_attempt}`;
  return `## Deployment inputs

Application version: \`${manifest.version}\`. [Source CI run](${ci}).
${manifestUrl ? `[Download image manifest](${manifestUrl}).\n` : ''}
Open [B. Deploy or Roll Back](${base}/deploy-dev.yml), select branch \`main\`, and enter each field below.

| Form field | Value to enter |
|---|---|
| \`image\` | \`${manifest.image}\` |
| \`alloy_image\` | Reuse an existing validated Alloy digest, or copy \`alloy_image\` from [I. Publish Alloy Image](${base}/publish-alloy.yml). |
| \`source_sha\` | \`${manifest.source_sha}\` |
| \`action\` | \`deploy\` |
| \`first_release\` | \`false\` — leave unchecked for an existing environment. |

Or set the Alloy digest and run:

\`\`\`bash
ALLOY_IMAGE='<paste the published Alloy repository@sha256:digest>'
gh workflow run deploy-dev.yml --repo ${repository} --ref main \\
  -f image='${manifest.image}' \\
  -f source_sha='${manifest.source_sha}' \\
  -f alloy_image="$ALLOY_IMAGE" \\
  -f action=deploy -f first_release=false
\`\`\`

Use \`first_release=true\` only for the initial deployment after database bootstrap.
Use the application's \`source_sha\` above, not the Alloy source commit.
Publication does not deploy ECS. The deployment environment's AWS, state, API and credential settings must already be configured.
`;
}

module.exports = {verifyImage, verifyBuildVersion, deploymentSummary};
