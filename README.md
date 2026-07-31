# Mini SSH

Cliente SSH Android mínimo para conectarse a un servidor mediante usuario y contraseña.

## MVP
- Host, puerto, usuario y contraseña.
- Huella SHA-256 del servidor obligatoria.
- Verificación de identidad del servidor antes de autenticar.
- Sesión shell interactiva.
- Consola de salida.
- Envío de comandos.
- Recuerda host, puerto, usuario y huella.
- La contraseña no se guarda.

## Obtener la huella en Ubuntu

Para la clave ED25519 del servidor:

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Copia el valor que empieza por `SHA256:` y pégalo en Mini SSH.

Si la huella presentada por el servidor no coincide, Mini SSH bloquea la conexión.
