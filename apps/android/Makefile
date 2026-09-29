# Palique Android — build & deployment shortcuts.
#
#   make deploy               Build + install + push whisper model + launch
#   make deploy ENGINE=deepseek   OpenAI-compatible remote (needs DEEPSEEK_API_KEY at runtime)
#   make deploy ENGINE=glm        OpenAI-compatible remote (needs ZHIPUAI_API_KEY at runtime)
#   make deploy-fast          Same as deploy, reusing the existing APK (no Gradle build)
#   make build / test / run / logs / models-push / clean
#
# The on-device llama.cpp engine (ENGINE=local) was removed on 2026-09-17.
# The whisper STT model is still pushed: deepseek/glm voice runs STT/TTS on
# device and the LLM remotely.
#
# With several devices connected the deploy aborts and lists them:
#   make deploy SERIAL=47270DLAQ0008U

ENGINE ?= $(if $(APP_CONVERSATION_ENGINE),$(APP_CONVERSATION_ENGINE),openai)
SERIAL ?=
ROOT := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
PKG := $(shell sed -n 's/^ANDROID_APPLICATION_ID=//p' $(ROOT)../../branding.properties)
GRADLE_FLAGS := -Papp.conversationEngine=$(ENGINE)
RESOLVE := ./scripts/adb-device.sh $(if $(SERIAL),"$(SERIAL)")

.PHONY: help deploy deploy-fast build test run logs models-push clean

help:
	@grep -E '^#   (make|PALIQUE)' Makefile | sed 's/^#   //'

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
	@serial=$$( $(RESOLVE) ) && adb -s $$serial shell am start -n $(PKG)/com.jkdc.app.MainActivity

logs:
	@serial=$$( $(RESOLVE) ) && adb -s $$serial logcat -s AppLocalEngine

clean:
	./gradlew clean
