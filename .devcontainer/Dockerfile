# See here for image contents: https://github.com/microsoft/vscode-dev-containers/tree/v0.177.0/containers/ubuntu/.devcontainer/base.Dockerfile

ARG VARIANT="focal"
FROM mcr.microsoft.com/vscode/devcontainers/base:0-${VARIANT}

RUN apt-get update
RUN apt install lsb-release wget software-properties-common
RUN wget https://apt.llvm.org/llvm.sh \
  && chmod +x llvm.sh \
  && sudo ./llvm.sh 12 \
  && rm llvm.sh
RUN bash -c "$(wget -O - https://apt.llvm.org/llvm.sh)"
RUN update-alternatives --install /usr/bin/clang clang /usr/bin/clang-12 120 --slave /usr/bin/clang++ clang++ /usr/bin/clang++-12

RUN export DEBIAN_FRONTEND=noninteractive \
  && apt-get -y install --no-install-recommends swig=3.0.12-1

RUN export DEBIAN_FRONTEND=noninteractive \
  && apt-get -y install --no-install-recommends openjdk-11-jre-headless=11.0.11+9-0ubuntu2~18.04

RUN export DEBIAN_FRONTEND=noninteractive \
  && apt-get -y install --no-install-recommends vim=2:8.0.1453-1ubuntu1.4

RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

ARG USERNAME=vscode
RUN SNIPPET="export PROMPT_COMMAND='history -a' && export HISTFILE=/commandhistory/.bash_history" \
    && mkdir /commandhistory \
    && touch /commandhistory/.bash_history \
    && chown -R $USERNAME /commandhistory \
    && echo $SNIPPET >> "/home/$USERNAME/.bashrc"

COPY library-scripts/docker-debian.sh /tmp/library-scripts/
RUN apt-get update && bash /tmp/library-scripts/docker-debian.sh

USER vscode
RUN mkdir -p ~/.local/bin
RUN wget -O ~/.local/bin/bazel https://github.com/bazelbuild/bazelisk/releases/download/v1.9.0/bazelisk-linux-amd64 \
  && chmod 755 ~/.local/bin/bazel

RUN wget -O ~/.local/bin/buildifier https://github.com/bazelbuild/buildtools/releases/download/4.0.1/buildifier-linux-amd64 \
  && chmod 755 ~/.local/bin/buildifier

RUN wget -O ~/.local/bin/kind https://github.com/kubernetes-sigs/kind/releases/download/v0.11.0/kind-linux-amd64 \
  && chmod 755 ~/.local/bin/kind

RUN wget -O ~/.local/bin/kubectl https://dl.k8s.io/release/v1.21.0/bin/linux/amd64/kubectl \
  && chmod 755 ~/.local/bin/kubectl

RUN mkdir ~/.cache

COPY home/* /home/$USERNAME/

ENTRYPOINT ["/usr/local/share/docker-init.sh"]
CMD ["sleep", "infinity"]
