FROM codercom/code-server:4.121.0

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

USER coder
RUN code-server --install-extension ms-python.python

WORKDIR /home/coder/boxloom
