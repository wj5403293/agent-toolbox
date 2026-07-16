# 将静态编译的 git 二进制放到此目录，文件名必须为 'git'
# 构建方法见 tools/build_static_git.sh
# 或: docker build -t git-builder tools/ && docker create --name git-out git-builder && docker cp git-out:/output/git assets/git/git && docker rm git-out
