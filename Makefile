# Palique — comandos principales de desarrollo.
# Todo el backend corre por Docker (services/api/compose.yaml); las apps
# móviles se compilan/instalan de forma nativa porque necesitan Xcode y el
# SDK de Android del host. `make up` levanta API, base de datos, panel de
# administración y Caddy con un solo comando.

SHELL := /bin/bash
COMPOSE := docker compose --project-directory $(CURDIR)/services/api -f $(CURDIR)/services/api/compose.yaml
SMOKE := docker compose --project-directory $(CURDIR)/services/api/tests -p looro-smoke -f $(CURDIR)/services/api/tests/compose.smoke.yaml
GRADLE := cd apps/android && ./gradlew
XCODEPROJ := apps/ios/App.xcodeproj
# Nombre del simulador usado por ios-test (docs/build-and-test.md).
IOS_SIMULATOR := iPhone 17

.DEFAULT_GOAL := help
.PHONY: help up down restart logs status health migrate psql admin-cli admin-add \
  admin-passwd admin-open test-api smoke-up smoke-down android-build android-test-docker android-install \
  android-run android-logcat ios-build ios-test ios-device check clean

help: ## Muestra esta ayuda
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36mmake %-16s\033[0m %s\n", $$1, $$2}'

# ---- Backend (Docker) -----------------------------------------------------

up: ## Levanta todo el stack local (BD, migraciones, API, panel admin)
	$(COMPOSE) up --build -d
	@echo ""
	@echo "API:         https://localhost/healthz  (cert interno de Caddy; usa -k en curl)"
	@echo "Panel admin: https://admin.localhost"
	@echo "Crea tu operador con: make admin-add USER=<nombre> EMAIL=<email>"

down: ## Para el stack conservando los datos (volúmenes postgres_data y caddy_data)
	$(COMPOSE) down

restart: ## Reinicia el stack
	$(COMPOSE) restart api caddy

logs: ## Sigue los logs de la API
	$(COMPOSE) logs -f --tail=100 api

status: ## Estado de los contenedores
	$(COMPOSE) ps

health: ## Comprueba /healthz y /readyz de la API
	@curl -sk https://localhost/healthz && echo
	@curl -sk https://localhost/readyz && echo

migrate: ## Aplica migraciones pendientes (up ya lo hace al arrancar)
	$(COMPOSE) run --rm migrate

psql: ## Consola psql contra la base de datos local
	$(COMPOSE) exec database psql -U app -d app

# ---- Panel de administración ----------------------------------------------

admin-cli: ## Ejecuta admins-admin en el contenedor: make admin-cli ARGS="list"
	$(COMPOSE) exec api node dist/src/admins-admin.js $(ARGS)

admin-add: ## Crea operador del panel: make admin-add USER=joaquin EMAIL=you@example.test
	$(COMPOSE) exec api node dist/src/admins-admin.js add local $(USER) $(EMAIL)

admin-passwd: ## Cambia contraseña de operador: make admin-passwd ADMIN_ID=<id>
	@read -rs -p "Contraseña: " pw; echo; \
	  printf '%s' "$$pw" | $(COMPOSE) exec -T api node dist/src/admins-admin.js passwd $(ADMIN_ID)

admin-open: ## Abre el panel en el navegador
	xdg-open https://admin.localhost

# ---- Pruebas ---------------------------------------------------------------

test-api: ## Suite de integración de la API en stack aislado (sin tocar tus datos)
	$(SMOKE) up --build -d api
	$(SMOKE) run --rm tests
	$(SMOKE) down --volumes

smoke-up: ## Levanta solo el stack de humo (PostgreSQL + API efímera)
	$(SMOKE) up --build -d api
	@echo "API de humo: $$( $(SMOKE) port api 8080 )"

smoke-down: ## Para el stack de humo y borra sus volúmenes
	$(SMOKE) down --volumes

check: ## Contratos multiplataforma que corre CI (contenido generado y Swift↔Kotlin)
	python3 -m unittest discover -s scripts/tests -t .
	python3 scripts/export_android_content.py --check
	python3 scripts/check_cross_platform.py

clean: ## Para el stack y elimina contenedores de humo
	$(COMPOSE) down
	$(SMOKE) down --volumes 2>/dev/null || true

# ---- Android (nativo; necesita SDK 36 y Java 17) ---------------------------

android-build: ## Compila APK debug con tests unitarios y lint (SDK/JDK del host)
	$(GRADLE) :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug

android-test-docker: ## Ejecuta las suites JVM Android dentro de Docker con cache Gradle persistente
	apps/android/docker/test-unit.sh

android-install: ## Instala el APK debug en el dispositivo/emulador conectado
	adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk

android-run: ## Lanza la app en el dispositivo conectado
	adb shell am start -n com.jkdc.palique.android/com.jkdc.palique.MainActivity

android-logcat: ## Logs de la app en el dispositivo
	adb logcat --pid=$$(adb shell pidof -s com.jkdc.palique.android)

# ---- iOS (nativo; necesita Xcode 26+ en macOS) -----------------------------

ios-build: ## Compila para simulador sin firmar
	xcodebuild -project $(XCODEPROJ) -scheme App \
	  -destination 'generic/platform=iOS Simulator' \
	  -derivedDataPath .build/DerivedData \
	  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build

ios-test: ## Tests core + UI en el simulador $(IOS_SIMULATOR)
	xcodebuild -project $(XCODEPROJ) -scheme App \
	  -destination 'platform=iOS Simulator,name=$(IOS_SIMULATOR),arch=arm64' \
	  -derivedDataPath .build/DerivedData \
	  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
	  -parallel-testing-enabled NO test

ios-device: ## Compila e instala en el iPhone conectado (requiere equipo de firma)
	xcodebuild -project $(XCODEPROJ) -scheme App \
	  -destination 'generic/platform=iOS' \
	  -derivedDataPath .build/DerivedData build
	xcrun devicectl device install app \
	  --device $$(xcrun devicectl list devices -q | head -1) \
	  .build/DerivedData/Build/Products/Debug-iphoneos/App.app
