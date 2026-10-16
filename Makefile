GRADLE := ./gradlew --no-daemon

.PHONY: setup test build test-device test-db lint locks serve

# First-time dependency download; subsequent default tests need no network or account.
setup:
	$(GRADLE) :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest

test:
	$(GRADLE) --offline :app:testDebugUnitTest

build:
	$(GRADLE) --offline :app:assembleDebug

test-device:
	$(GRADLE) --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vivekray898.payalert.team.TeamScreenTest

# A disposable EMPTY PostgreSQL database only. Never point this at a real Supabase project.
test-db:
	@test -n "$(TEST_DATABASE_URL)" || (printf '%s\n' 'Set TEST_DATABASE_URL to a disposable empty PostgreSQL database.'; exit 1)
	psql "$(TEST_DATABASE_URL)" -X -v ON_ERROR_STOP=1 -v shim=true -f supabase/tests/team_pairing.sql

lint:
	$(GRADLE) --offline :app:lintDebug

locks:
	$(GRADLE) :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --write-locks

# APK download preview, not a web version of the Android app.
serve:
	python3 -m http.server 8080 --directory app/build/outputs/apk/debug
