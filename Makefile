.PHONY: verify test lint contract build image migrate smoke
verify: lint contract test
lint:
	./mvnw -B --no-transfer-progress checkstyle:check
	node --test .github/scripts/*.test.cjs
contract:
	scripts/validate-api.sh
test:
	scripts/test.sh
build:
	./mvnw -B --no-transfer-progress -DskipTests package
image:
	docker build -f Dockerfile.local -t banking-api:local .
migrate:
	scripts/migrate.sh
smoke: image
	docker build -t banking-alloy:local ../deployment/deploy/monitoring
	scripts/local-smoke.sh
