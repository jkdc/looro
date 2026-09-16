# Mural Android — build & deployment shortcuts.
#
#   make deploy              Build + install + (ENGINE=local: push models) + launch
#   make deploy ENGINE=local On-device llama.cpp engine (offline inference)
#   make deploy ENGINE=deepseek   OpenAI-compatible remote (needs DEEPSEEK_API_KEY at runtime)
#   make deploy ENGINE=glm        OpenAI-compatible remote (needs ZHIPUAI_API_KEY at runtime)
#   MURAL_CONVERSATION_ENGINE=local make deploy    (environment-variable form)
#   make deploy-fast         Same as deploy, reusing the existing APK (no Gradle build)
#   make build / test / run / logs / models-push / clean
#
# With several devices connected:  make deploy SERIAL=<serial>

ENGINE ?= $(if $(MURAL_CONVERSATION_ENGINE),$(MURAL_CONVERSATION_ENGINE),openai)
SERIAL ?=
ADB := adb $(if $(SERIAL),-s $(SERIAL),)
GRADLE_FLAGS := -Pmural.conversationEngine=$(ENGINE)
PKG := chat.mural.android

.PHONY: help deploy deploy-fast build test run logs models-push clean

help:
	@grep -E '^#   (make|MURAL)' Makefile | sed 's/^#   //'

deploy:
ifndef DEPLOY_NO_BUILD
	./gradlew :app:assembleDebug $(GRADLE_FLAGS)
endif
	$(ADB) install -r -t app/build/outputs/apk/debug/app-debug.apk
	$(ADB) shell pm grant $(PKG) android.permission.RECORD_AUDIO 2>/dev/null || true
	@if [ "$(ENGINE)" = "local" ]; then SERIAL=$(SERIAL) ./scripts/push-local-models.sh; fi
	$(ADB) shell am force-stop $(PKG)
	$(ADB) shell am start -n $(PKG)/chat.mural.MainActivity
	@echo "Deployed with ENGINE=$(ENGINE)."

deploy-fast:
	$(MAKE) DEPLOY_NO_BUILD=1 deploy

build:
	./gradlew :app:assembleDebug $(GRADLE_FLAGS)

test:
	./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest

models-push:
	SERIAL=$(SERIAL) ./scripts/push-local-models.sh

run:
	$(ADB) shell am start -n $(PKG)/chat.mural.MainActivity

logs:
	$(ADB) logcat -s MuralLocalEngine

clean:
	./gradlew clean
