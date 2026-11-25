.PHONY: check check-backend check-frontend check-policy check-commits check-test-docker

BASE_REF ?= origin/main
HEAD_REF ?= HEAD

check: check-policy check-backend check-frontend

check-backend:
	@if [ -f freesia-admin/pom.xml ]; then \
		cd freesia-admin && mvn -B test; \
	elif git cat-file -e HEAD:freesia-admin/pom.xml 2>/dev/null; then \
		echo 'error: committed backend entrypoint is missing' >&2; exit 1; \
	else \
		echo 'Policy-only bootstrap: backend package is not committed yet.'; \
	fi

check-frontend:
	@if [ -f freesia-web/package.json ]; then \
		pnpm --dir freesia-web lint && pnpm --dir freesia-web build; \
	elif git cat-file -e HEAD:freesia-web/package.json 2>/dev/null; then \
		echo 'error: committed frontend entrypoint is missing' >&2; exit 1; \
	else \
		echo 'Policy-only bootstrap: frontend package is not committed yet.'; \
	fi

check-policy:
	python3 scripts/repository_policy.py self-check
	python3 -m unittest discover -s tests/repository_policy -p 'test_*.py'

check-commits:
	python3 scripts/repository_policy.py commits "$(BASE_REF)" "$(HEAD_REF)"

check-test-docker:
	python3 scripts/check_test_docker.py
