PROJECT_NAME := jadx-nr-plugin
# 检测操作系统
ifeq ($(OS),Windows_NT)
    PLATFORM := windows
    GRADLE_CMD := gradlew.bat
    # 使用 Windows 环境变量路径并转义
    JADX_PLUGINS_DIR := $(subst \,/,$(USERPROFILE))/.jadx/plugins
    # 定义跨平台命令
    MKDIR := mkdir
    RM := del /Q /F
    CP := copy /Y
else
    PLATFORM := unix
    GRADLE_CMD := ./gradlew
    JADX_PLUGINS_DIR := $(HOME)/.jadx/plugins
    MKDIR := mkdir -p
    RM := rm -f
    CP := cp -f
endif

.PHONY: all clean build deploy info help

all: clean deploy

clean:
	@echo "=== 清理旧构建产物 ==="
	$(GRADLE_CMD) clean
	@echo "=== 清理 JADX 插件目录旧版本 ==="
	# 针对不同平台处理清理逻辑，避免 shell 报错
ifeq ($(PLATFORM),windows)
	@if exist "$(subst /,\,$(JADX_PLUGINS_DIR))" $(RM) "$(subst /,\,$(JADX_PLUGINS_DIR))\\$(PROJECT_NAME)-*.jar"
else
	@rm -f $(JADX_PLUGINS_DIR)/$(PROJECT_NAME)-*.jar
endif

build:
	@echo "=== 编译生成 Jar 包 (shadowJar) ==="
	$(GRADLE_CMD) shadowJar

deploy: build
	@echo "=== 部署 Jar 包到 JADX 插件目录 ==="
ifeq ($(PLATFORM),windows)
	@if not exist "$(subst /,\,$(JADX_PLUGINS_DIR))" mkdir "$(subst /,\,$(JADX_PLUGINS_DIR))"
	@copy /Y "build\\libs\\$(PROJECT_NAME)-*.jar" "$(subst /,\,$(JADX_PLUGINS_DIR))\\"
else
	@mkdir -p $(JADX_PLUGINS_DIR)
	@cp -f ./build/libs/$(PROJECT_NAME)-*.jar $(JADX_PLUGINS_DIR)/
endif
	@echo "=== 部署完成！路径：$(JADX_PLUGINS_DIR) ==="

info:
	@echo "=== JADX 插件目录中的 Jar 包 ==="
ifeq ($(PLATFORM),windows)
	@dir "$(subst /,\,$(JADX_PLUGINS_DIR))\\$(PROJECT_NAME)-*.jar"
else
	@ls -lh $(JADX_PLUGINS_DIR)/$(PROJECT_NAME)-*.jar
endif

help:
	@echo "使用说明 (Current Platform: $(PLATFORM))："
	@echo "  make          - 一键清理 + 编译 + 部署"
	@echo "  make clean    - 清理本地与插件目录"
	@echo "  make build    - 仅编译 shadowJar"
	@echo "  make deploy   - 编译并拷贝到 JADX 目录"
