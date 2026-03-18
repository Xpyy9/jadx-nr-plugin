PROJECT_NAME := jadx-nr-plugin
GRADLE_CMD := $(if $(findstring Windows,$(OS)),gradlew.bat,./gradlew)

ifeq ($(OS),Windows_NT)
    JADX_PLUGINS_DIR := $(subst \,/,$(USERPROFILE))/.jadx/plugins
else
    JADX_PLUGINS_DIR := $(HOME)/.jadx/plugins
endif

all: clean build deploy
clean:
	@echo "=== 清理旧构建产物 ==="
	$(GRADLE_CMD) clean
	@if [ -d "$(JADX_PLUGINS_DIR)" ]; then \
		echo "=== 清理JADX插件目录旧版本 ==="; \
		rm -f $(JADX_PLUGINS_DIR)/$(PROJECT_NAME)-*.jar; \
	fi

build:
	@echo "=== 编译生成Jar包 ==="
	$(GRADLE_CMD) shadowJar
	$(GRADLE_CMD) dist

deploy: build
	@echo "=== 部署Jar包到JADX插件目录 ==="
	@mkdir -p $(JADX_PLUGINS_DIR)
	@cp -f ./build/dist/$(PROJECT_NAME)-*.jar $(JADX_PLUGINS_DIR)/
	@echo "=== 部署完成！JADX插件目录：$(JADX_PLUGINS_DIR) ==="

info:
	@echo "=== 本地生成的Jar包 ==="
	@find ./build/dist -name "$(PROJECT_NAME)-*.jar" -print
	@echo "=== JADX插件目录中的Jar包 ==="
	@find $(JADX_PLUGINS_DIR) -name "$(PROJECT_NAME)-*.jar" -print

help:
	@echo "使用说明："
	@echo "  make          - 一键清理+编译+部署到JADX插件目录（默认）"
	@echo "  make clean    - 清理本地构建产物+JADX插件目录旧版本"
	@echo "  make build    - 仅编译生成Jar包（不部署）"
	@echo "  make deploy   - 编译后部署到JADX插件目录"
	@echo "  make info     - 查看本地和JADX插件目录的Jar包"
	@echo "  make help     - 显示帮助信息"

.PHONY: all clean build deploy info help
