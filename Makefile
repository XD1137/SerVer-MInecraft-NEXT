# NekoGate / NekoProxy build shortcuts
# Requires: cargo (Rust), gradle, and a JDK on PATH.
# Only the ViaProxy plugin is installed into ./plugins/.

.PHONY: help all build rust plugin authme-bridge run fmt fmt-check test clean

.DEFAULT_GOAL := help

help:
	@echo Available targets:
	@echo   make all             Build Rust proxy, ViaProxy plugin, and AuthMe bridge
	@echo   make build           Alias for all
	@echo   make rust            Build the Rust proxy in release mode
	@echo   make plugin          Build and install the ViaProxy plugin into ./plugins/
	@echo   make authme-bridge   Build the AuthMe bridge for backend installation
	@echo   make run             Run the Rust proxy (pass extra args with ARGS=...)
	@echo   make fmt             Format Rust sources
	@echo   make fmt-check       Check Rust formatting without changing files
	@echo   make test            Run Rust tests
	@echo   make clean           Remove Rust and Java build outputs

all: rust plugin authme-bridge

build: all

rust:
	cargo build --release

plugin:
	gradle -p viaproxy-plugin clean installPlugin

authme-bridge:
	gradle -p authme-bridge clean build

run:
	cargo run --release -- $(ARGS)

fmt:
	cargo fmt

fmt-check:
	cargo fmt --check

test:
	cargo test

clean:
	cargo clean
	gradle -p viaproxy-plugin clean
	gradle -p authme-bridge clean

# Windows users without GNU Make can run:
#   powershell -ExecutionPolicy Bypass -File scripts/build.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Target plugin
#
# Example:
#   make run ARGS='--motd "NekoProxy | Shiguangxu" --log-level DEBUG'
#   make run ARGS='--listen 0.0.0.0:25566 --target play.example.com:25565'
