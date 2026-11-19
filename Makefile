# Palique — comandos principales de desarrollo.
# Todo el backend corre por Docker (services/api/compose.yaml); las apps
# móviles se compilan/instalan de forma nativa porque necesitan Xcode y el
# SDK de Android del host. `make up` levanta API, base de datos, panel de
# administración y Caddy con un solo comando.

SHELL := /bin/bash
COMPOSE := docker compose --project-directory $(CURDIR)/services/api -f $(CURDIR)/services/api/compose.yaml
SMOKE := docker compose --project-directory $(CURDIR)/services/api/tests -p looro-smoke -f $(CURDIR)/services/api/tests/compose.smoke.yaml
LOAD_RUNNER_COMPOSE := docker compose --project-directory $(CURDIR)/services/api/load -f $(CURDIR)/services/api/load/compose.runner.yaml
PYTHON_CHECK_IMAGE ?= python:3.12-alpine
# Laboratorio móvil en la LAN: el mismo stack con la API publicada en claro
# (solo builds debug; ver docs/local-android-lab.md).
LAB_COMPOSE := $(COMPOSE) -f $(CURDIR)/services/api/compose.lab.yaml
LAB_PORT ?= 8080
LAB_HOST_IP ?= $(shell ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($$i=="src"){print $$(i+1); exit}}')
# Despliegue al móvil conectado (make deploy): motor de conversación, serial adb
# (obligatorio si hay varios dispositivos) y adopción de cuenta de laboratorio.
DEPLOY_ENGINE ?= glm
DEPLOY_SERIAL ?=
SERIAL ?= $(DEPLOY_SERIAL)
GRADLE := cd apps/android && ./gradlew
XCODEPROJ := apps/ios/App.xcodeproj
# Nombre del simulador usado por ios-test (docs/build-and-test.md).
IOS_SIMULATOR := iPhone 17

.DEFAULT_GOAL := help
.PHONY: help up down restart logs status health migrate psql admin-cli admin-add \
  admin-passwd admin-open obs-check test-api smoke-up smoke-down \
  lab-keys lab-up lab-down lab-account lab-apk lab-install deploy \
  remote-apk remote-install \
  android-build android-test-docker android-install \
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

obs-check: ## Valida configs y comportamiento de alertas (compose config, promtool, amtool)
	docker compose --project-directory $(CURDIR)/services/api -f $(CURDIR)/services/api/compose.yaml config -q
	docker run --rm -v $(CURDIR)/services/api/telemetry:/telemetry:ro --entrypoint promtool prom/prometheus:v2.54.1 check rules /telemetry/alerts.yml
	docker run --rm -v $(CURDIR)/services/api/telemetry:/telemetry:ro --entrypoint promtool prom/prometheus:v2.54.1 test rules /telemetry/alerts.test.yml
	docker run --rm -v $(CURDIR)/services/api/telemetry:/telemetry:ro --entrypoint amtool prom/alertmanager:v0.27.0 check-config /telemetry/alertmanager.yml

migrate: ## Aplica migraciones pendientes (up ya lo hace al arrancar)
	$(COMPOSE) build migrate
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

test-api: ## Suite API en stack aislado; limpia al terminar (KEEP_SMOKE_STACK=1 conserva para diagnóstico)
	@set -u; \
	keep="$${KEEP_SMOKE_STACK:-0}"; \
	case "$$keep" in 0|1) ;; *) echo "KEEP_SMOKE_STACK must be 0 or 1" >&2; exit 2 ;; esac; \
	cleanup() { \
	  result=$$?; \
	  trap - EXIT; \
	  if [ "$$keep" = 1 ]; then \
	    echo "Keeping isolated looro-smoke stack; stop it with make smoke-down"; \
	  else \
	    $(SMOKE) down --volumes; cleanup_result=$$?; \
	    if [ "$$result" -eq 0 ] && [ "$$cleanup_result" -ne 0 ]; then result=$$cleanup_result; fi; \
	  fi; \
	  exit "$$result"; \
	}; \
	trap cleanup EXIT; trap 'exit 130' INT; trap 'exit 143' TERM; \
	$(SMOKE) up --build -d api && $(SMOKE) run --rm tests

smoke-up: ## Levanta solo el stack de humo (PostgreSQL + API efímera)
	$(SMOKE) up --build -d api
	@echo "API de humo: $$( $(SMOKE) port api 8080 )"

smoke-down: ## Para el stack de humo y borra sus volúmenes
	$(SMOKE) down --volumes

check: ## Contratos multiplataforma que corre CI (contenido generado y Swift↔Kotlin)
	docker run --rm -v $(CURDIR):/workspace -w /workspace $(PYTHON_CHECK_IMAGE) sh -ec 'python3 -m unittest discover -s scripts/tests -t . && python3 scripts/export_progress_sync.py --check && python3 scripts/export_android_content.py --check && python3 scripts/check_cross_platform.py && python3 scripts/check_ui_strings.py'

clean: ## Para el stack y elimina contenedores de humo
	$(COMPOSE) down
	$(SMOKE) down --volumes 2>/dev/null || true

# ---- Laboratorio móvil en la LAN (solo builds debug) -----------------------

lab-keys: ## Genera los secretos de cuentas del laboratorio en services/api/.env
	docker run --rm -v $(CURDIR):/workspace -w /workspace $(PYTHON_CHECK_IMAGE) python3 scripts/lab_env_keys.py /workspace/services/api/.env

lab-up: lab-keys ## Levanta el stack con la API publicada en la LAN (LAB_PORT=8080)
	@test -n "$(LAB_HOST_IP)" || { echo "No pude detectar la IP LAN; pasa LAB_HOST_IP=<ip>." >&2; exit 1; }
	@# Stop first so the outgoing worker releases its advisory lock before the
	@# replacement starts; otherwise the new container can crash-loop briefly.
	-$(LAB_COMPOSE) stop api >/dev/null 2>&1
	$(LAB_COMPOSE) up -d
	@echo ""
	@echo "API del laboratorio: http://$(LAB_HOST_IP):$(LAB_PORT)/healthz"
	@echo "Siguiente paso:      make lab-install"

lab-down: ## Para el stack del laboratorio (conserva los volúmenes)
	$(LAB_COMPOSE) down

lab-account: ## Crea la cuenta de laboratorio y muestra el comando adb para adoptarla
	@token=$$(docker run --rm $(PYTHON_CHECK_IMAGE) python3 -c 'import secrets; print(secrets.token_urlsafe(32))'); \
	hash=$$(docker run --rm $(PYTHON_CHECK_IMAGE) python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' "$$token"); \
	id=$$($(LAB_COMPOSE) exec -T database psql -U app -d app -tA -v ON_ERROR_STOP=1 -v hash="$$hash" -f - < services/api/lab-account.sql | tr -d '[:space:]'); \
	case "$$id" in \
	  ????????-????-????-????-????????????) ;; \
	  *) echo "No se pudo crear la cuenta de laboratorio (¿está levantado make lab-up?)." >&2; exit 1 ;; \
	esac; \
	echo "LAB_ACCOUNT_ID=$$id"; \
	echo ""; \
	echo "Adopta la sesión en el dispositivo (build debug):"; \
	echo "adb shell am start -n com.jkdc.palique.android/com.jkdc.app.MainActivity --es debug.accountID $$id --es debug.accountToken $$token"

lab-apk: ## Compila un APK debug apuntando a la IP LAN de este equipo
	@test -n "$(LAB_HOST_IP)" || { echo "No pude detectar la IP LAN; pasa LAB_HOST_IP=<ip>." >&2; exit 1; }
	$(GRADLE) :app:assembleDebug -Papp.apiOrigin=http://$(LAB_HOST_IP):$(LAB_PORT)

lab-install: lab-apk ## Compila el APK del laboratorio y lo instala en el dispositivo conectado
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(SERIAL))" install -r apps/android/app/build/outputs/apk/debug/app-debug.apk

deploy: lab-up ## Despliega en el móvil conectado: API de lab + APK con claves de prueba + micrófono + lanzamiento (ENGINE=glm SERIAL= ACCOUNT=1)
	@test -n "$(LAB_HOST_IP)" || { echo "No pude detectar la IP LAN; pasa LAB_HOST_IP=<ip>." >&2; exit 1; }
	bash apps/android/scripts/deploy-lab.sh --origin http://$(LAB_HOST_IP):$(LAB_PORT) --engine $(DEPLOY_ENGINE) \
	  $(if $(DEPLOY_SERIAL),--serial $(DEPLOY_SERIAL),) $(if $(filter 1,$(ACCOUNT)),--account,)

# ---- Android (nativo; necesita SDK 36 y Java 17) ---------------------------

# Contrato de despliegue móvil (no cambiar sin leer docs/deployment-coolify.md):
# - La app SIEMPRE usa el motor `api`: whisper STT en el dispositivo y el texto
#   va al backend (`/v1/proxy/responses`), que resuelve proveedor/modelo desde
#   su panel. Nunca una conexión directa del móvil a un proveedor LLM.
# - El client ID de Google es obligatorio: sin él el cliente de cuenta se
#   desactiva silenciosamente y la app no llama nunca al servidor.
# - Los modelos whisper se empujan siempre tras instalar (push-local-models).
REMOTE_API_ORIGIN ?= https://palique-api.46.202.135.91.sslip.io
REMOTE_CONVERSATION_ENGINE ?= api
REMOTE_GOOGLE_CLIENT_ID ?= 1234567890-paliquetest.apps.googleusercontent.com
REMOTE_SERIAL ?=

remote-apk: ## Compila el APK contra la API de Coolify (motor api = proxy del backend) y verifica su contenido
	$(GRADLE) :app:assembleDebug -Papp.apiOrigin=$(REMOTE_API_ORIGIN) -Papp.conversationEngine=$(REMOTE_CONVERSATION_ENGINE) -Papp.googleServerClientID=$(REMOTE_GOOGLE_CLIENT_ID)
	bash apps/android/scripts/verify-remote-apk.sh apps/android/app/build/outputs/apk/debug/app-debug.apk 	  "$$(echo $(REMOTE_API_ORIGIN) | sed 's|https://||')" 	  "$$(echo $(REMOTE_GOOGLE_CLIENT_ID) | sed 's/-.*//;s|^1234567890-||')" 	  $(REMOTE_CONVERSATION_ENGINE)

remote-install: remote-apk ## Instala en el dispositivo el APK completo del contrato (sesión no incluida: usar el seam debug o Google)
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(REMOTE_SERIAL))" install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(REMOTE_SERIAL))" shell pm grant com.jkdc.palique.android android.permission.RECORD_AUDIO || true
	SERIAL=$(REMOTE_SERIAL) bash apps/android/scripts/push-local-models.sh
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(REMOTE_SERIAL))" shell am start -n com.jkdc.palique.android/com.jkdc.app.MainActivity

android-build: ## Compila APK debug con tests unitarios y lint (SDK/JDK del host)
	$(GRADLE) :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug

android-test-docker: ## Ejecuta las suites JVM Android dentro de Docker con cache Gradle persistente
	apps/android/docker/test-unit.sh

android-install: ## Instala el APK debug en el dispositivo/emulador conectado
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(SERIAL))" install -r apps/android/app/build/outputs/apk/debug/app-debug.apk

android-run: ## Lanza la app en el dispositivo conectado
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(SERIAL))" shell am start -n com.jkdc.palique.android/com.jkdc.app.MainActivity

android-logcat: ## Logs de la app en el dispositivo
	adb -s "$$(bash apps/android/scripts/adb-device.sh $(SERIAL))" logcat --pid=$$(adb -s "$$(bash apps/android/scripts/adb-device.sh $(SERIAL))" shell pidof -s com.jkdc.palique.android)

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

.PHONY: load-api-runner-test load-api-smoke load-api-baseline
load-api-runner-test: ## Comprueba el aislamiento del contenedor de herramientas de carga
	$(LOAD_RUNNER_COMPOSE) run --build --rm --entrypoint node load-runner --test /workspace/services/api/load/runner.test.mjs

load-api-smoke: ## Prueba de carga corta con fixtures y proveedor aislados
	$(LOAD_RUNNER_COMPOSE) run --build --rm load-runner smoke

load-api-baseline: ## Señal local de capacidad; no certifica producción
	$(LOAD_RUNNER_COMPOSE) run --build --rm load-runner baseline
