#!/bin/bash
#
# SQL Parser Demo01 - 构建与测试脚本
# 用法: ./build.sh [命令]
#
# 命令:
#   compile   - 编译项目
#   test      - 运行所有测试
#   run       - 运行主程序（示例演示）
#   clean     - 清理构建产物
#   all       - 清理 + 编译 + 测试 + 运行
#   help      - 显示帮助信息
#

set -e

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # 无颜色

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

print_header() {
    echo ""
    echo -e "${BLUE}======================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}======================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ️  $1${NC}"
}

# 编译
do_compile() {
    print_header "编译项目"
    if sbt compile; then
        print_success "编译成功"
    else
        print_error "编译失败"
        exit 1
    fi
}

# 测试
do_test() {
    print_header "运行测试"
    if sbt test; then
        print_success "所有测试通过"
    else
        print_error "存在测试失败"
        exit 1
    fi
}

# 运行主程序
do_run() {
    print_header "运行主程序"
    sbt "runMain com.mysql.parser.MySQLParser"
}

# 清理
do_clean() {
    print_header "清理构建产物"
    sbt clean
    print_success "清理完成"
}

# 全部执行
do_all() {
    do_clean
    do_compile
    do_test
    do_run
}

# 帮助
do_help() {
    echo ""
    echo "SQL Parser Demo01 - 构建与测试脚本"
    echo ""
    echo "用法: ./build.sh [命令]"
    echo ""
    echo "可用命令:"
    echo "  compile   编译项目"
    echo "  test      运行所有测试"
    echo "  run       运行主程序（示例演示）"
    echo "  clean     清理构建产物"
    echo "  all       清理 + 编译 + 测试 + 运行"
    echo "  help      显示本帮助信息"
    echo ""
    echo "示例:"
    echo "  ./build.sh compile    # 仅编译"
    echo "  ./build.sh test       # 仅测试"
    echo "  ./build.sh all        # 完整构建流程"
    echo ""
}

# 主入口
case "${1:-help}" in
    compile)  do_compile ;;
    test)     do_test ;;
    run)      do_run ;;
    clean)    do_clean ;;
    all)      do_all ;;
    help|*)   do_help ;;
esac
