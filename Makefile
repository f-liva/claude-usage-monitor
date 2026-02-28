SHELL := /bin/bash

# Paths
ANDROID_HOME ?= $(HOME)/android-sdk
APK_DEBUG    := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE  := releases/claude-monitor-v1.0-debug.apk
PACKAGE      := com.claudemonitor.app

# ADB: usa adb.exe di Windows (vede i dispositivi USB) se siamo in WSL
ADB_LINUX    := $(ANDROID_HOME)/platform-tools/adb
IS_WSL       := $(shell grep -qi microsoft /proc/version 2>/dev/null && echo 1)
ADB          := $(if $(IS_WSL),adb.exe,$(ADB_LINUX))

# In WSL, copia l'APK su un path Windows per adb.exe
ifeq ($(IS_WSL),1)
  WIN_TEMP     := /mnt/c/Users/$(shell cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r')/AppData/Local/Temp
  ADB_APK_SRC  = $(WIN_TEMP)/claude-monitor.apk
  ADB_APK      = $(shell wslpath -m $(ADB_APK_SRC))
  COPY_APK     = cp $(APK_RELEASE) $(ADB_APK_SRC)
else
  ADB_APK      = $(APK_RELEASE)
  COPY_APK     = true
endif

# Source SDKMAN per avere java nel PATH
SDKMAN_INIT  := source $(HOME)/.sdkman/bin/sdkman-init.sh &&

.PHONY: build push install clean rebuild info devices

## Compila APK debug
build:
	@echo "⚙️  Compilazione APK debug..."
	@$(SDKMAN_INIT) ANDROID_HOME=$(ANDROID_HOME) ./gradlew assembleDebug
	@cp $(APK_DEBUG) $(APK_RELEASE)
	@echo "✅ APK pronto: $(APK_RELEASE)"

## Compila e installa sul dispositivo connesso
push: build
	@echo "📲 Installazione su dispositivo..."
	@$(COPY_APK)
	@$(ADB) install -r "$(ADB_APK)"
	@echo "🚀 Avvio app..."
	@$(ADB) shell am start -n $(PACKAGE)/.ui.MainActivity
	@echo "✅ Fatto!"

## Solo installa (senza ricompilare)
install:
	@echo "📲 Installazione su dispositivo..."
	@$(COPY_APK)
	@$(ADB) install -r "$(ADB_APK)"
	@echo "🚀 Avvio app..."
	@$(ADB) shell am start -n $(PACKAGE)/.ui.MainActivity
	@echo "✅ Fatto!"

## Pulisci build
clean:
	@$(SDKMAN_INIT) ANDROID_HOME=$(ANDROID_HOME) ./gradlew clean

## Pulisci e ricompila
rebuild: clean build

## Mostra dispositivi connessi
devices:
	@$(ADB) devices -l

## Mostra versioni SDK/Java
info:
	@$(SDKMAN_INIT) java -version 2>&1
	@echo "ADB: $$($(ADB) version | head -1)"
	@$(SDKMAN_INIT) ./gradlew --version 2>/dev/null | grep "Gradle "

## Help
help:
	@echo "Comandi disponibili:"
	@echo "  make build     → Compila APK debug"
	@echo "  make push      → Compila + installa sul telefono"
	@echo "  make install   → Solo installa (senza ricompilare)"
	@echo "  make clean     → Pulisci build"
	@echo "  make rebuild   → Pulisci + ricompila"
	@echo "  make devices   → Mostra dispositivi ADB connessi"
	@echo "  make info      → Mostra versioni SDK/Java"
