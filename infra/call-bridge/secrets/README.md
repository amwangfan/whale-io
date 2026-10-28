# Secrets (not in git)

Provide credentials yourself; nothing under this directory is committed except `.gitkeep`.

- Put `SIGNAL_TOKEN` in the environment, or in a local file such as `secrets/signal_token` / `audio-bridge.env` (mode 600) that you create on the host.
- Do not commit token files, `.env`, or keystores.
