# Mini SSH

Cliente SSH Android mínimo con terminal y túnel VPN SSH fail-open.

## Funciones
- SSH por contraseña con huella SHA-256 obligatoria.
- Terminal interactiva.
- VPN Android para enviar tráfico TCP por Ubuntu.
- DNS remoto mediante MapDNS de hev-socks5-tunnel.
- Si SSH cae, la interfaz VPN se cierra para recuperar Internet normal.
- Notificación persistente con botón Desconectar.
- Botón Desconectar todo en la app.
- La contraseña no se guarda.

## VPN
La VPN usa `VpnService` + `hev-socks5-tunnel` y un proxy SOCKS5 local que abre conexiones `direct-tcpip` sobre SSH.

La versión 0.4 enruta TCP. UDP/QUIC no se tuneliza todavía; Chrome puede usar HTTPS sobre TCP cuando QUIC no está disponible.

## Huella Ubuntu
```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Copia el valor `SHA256:...` en la app.
