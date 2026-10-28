# Ring signaling hub

`ring_hub.py` listens on `SIGNAL_PORT` (default **18800**):

- `GET /health`
- `GET|POST /v1/ring?token=&device_id=&number=&name=&bridge_host=&bridge_port=`
- `GET /v1/poll?device_id=&wait=25` (long-poll for phone)
- `WS /ws?device_id=&token=` (optional)

Auth: `SIGNAL_TOKEN` via `?token=` or `Authorization: Bearer`.

Public host defaults `BRIDGE_PUBLIC_HOST=<PUBLIC_IP>` so ring payloads tell the phone where the audio bridge is.
