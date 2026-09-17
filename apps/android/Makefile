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
# With several devices connected the deploy aborts and lists them:
#   make deploy SERIAL=47270DLAQ0008U

ENGINE ?= $(if $(MURAL_CONVERSATION_ENGINE),$(MURAL_CONVERSATION_ENGINE),openai)
SERIAL ?=
PKG := chat.mural.android
GRADLE_FLAGS := -Pmural.conversationEngine=$(ENGINE)
RESOLVE := ./scripts/adb-device.sh $(if $(SERIAL),"$(SERIAL)")

.PHONY: help deploy deploy-fast build test run logs models-push clean

help:
	@grep -E '^#   (make|MURAL)' Makefile | sed 's/^#   //'

deploy:
	ENGINE=$(ENGINE) SERIAL=$(SERIAL) ./scripts/deploy-local.sh

deploy-fast:
	DEPLOY_NO_BUILD=1 ENGINE=$(ENGINE) SERIAL=$(SERIAL) ./scripts/deploy-local.sh

build:
	./gradlew :app:assembleDebug $(GRADLE_FLAGS)

test:
	./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest

models-push:
	SERIAL=$(SERIAL) ./scripts/push-local-models.sh

run:
	@serial=$$( $(RESOLVE) ) && adb -s $$serial shell am start -n $(PKG)/chat.mural.MainActivity

logs:
	@serial=$$( $(RESOLVE) ) && adb -s $$serial logcat -s MuralLocalEngine

clean:
	./gradlew clean
