# 静态编译 git for Android arm64
# 用法: docker build --platform linux/amd64 -t git-builder . && docker create --name git-out git-builder && docker cp git-out:/output/git ./assets/git/git && docker rm git-out
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq && \
    apt-get install -y -qq wget xz-utils bzip2 make autoconf automake libtool \
    pkg-config gettext curl ca-certificates python3 perl unzip && \
    rm -rf /var/lib/apt/lists/*

COPY build_static_git.sh /build.sh
RUN chmod +x /build.sh

RUN /build.sh
