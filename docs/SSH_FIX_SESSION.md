# Corrección de la desconexión SSH en `mini-ssh`

## Proyecto

- Proyecto: `F:\PROYECTOS CODEX\mini-ssh`
- Archivo principal: `app/src/main/java/com/pililo777/minissh/MainActivity.kt`
- Package: `com.pililo777.minissh`
- Biblioteca SSH: `com.github.mwiede:jsch:2.28.4`

## Problema inicial

La aplicación abría correctamente la sesión SSH y el `ChannelShell`:

```text
session connected
shell connected
read loop started
```

Sin embargo, al enviar el primer comando (`ls`) el canal terminaba en EOF. La interfaz cambiaba a `CONECTAR TERMINAL`, aunque el cierre no lo iniciaba el botón de desconexión.

El problema se reproducía:

- por la ruta pública `barelrancho.es:1234`;
- por LAN `192.168.1.115:22`;
- usando `CR` y `LF`;
- con AES-GCM;
- con `aes128-ctr + hmac-sha2-256`.

El cliente registraba `write_ok`, `flush_ok` y después EOF. El servidor Ubuntu/OpenSSH registraba:

```text
Bad packet length
message authentication code incorrect
```

## Investigación realizada

Se revisaron:

- listeners de `CONECTAR/DESCONECTAR`, `ENVIAR` y `CTRL`;
- `sendEnter()` y `sendBytes()`;
- serialización de escrituras mediante executor monohilo;
- read loop y cierre de streams;
- ciclo de vida de Activity;
- parser ANSI/emulador de terminal;
- configuración de `ChannelShell`;
- negociación criptográfica JSch;
- autenticación y configuración del servidor;
- logs de Logcat y `/var/log/auth.log`.

El servidor confirmó que la sesión se abría y que el fallo era un paquete SSH inválido posterior al envío. La ruta NAT no era la causa porque el mismo problema aparecía por LAN.

## Cronología

1. Se detecta EOF inmediatamente después del primer comando.
2. Se descarta que la desconexión provenga del botón `DESCONECTAR TERMINAL`.
3. Se descartan los terminadores `CR` y `LF` como causa.
4. Se reproduce el problema tanto por LAN como por la ruta pública.
5. Se descarta AES-GCM repitiendo la prueba con `aes128-ctr + hmac-sha2-256`.
6. Se demuestra que `ChannelExec` funciona correctamente.
7. Se demuestra que una implementación mínima de `ChannelShell` también funciona correctamente.
8. Se identifica `setPtySize()` como la diferencia funcional más relevante.
9. Se eliminan las llamadas activas a `setPtySize()`.
10. El problema desaparece y la sesión permanece estable.

## Pruebas de aislamiento

### `ChannelExec`

Se añadió temporalmente `PRUEBA EXEC`, usando una sesión y canal nuevos, sin PTY. Ejecutó:

```text
printf 'mini_ssh_exec_ok\n'
```

Resultado:

```text
stdout=mini_ssh_exec_ok
stderr=
exitStatus=0
```

Esto demostró que la autenticación, JSch, el proveedor criptográfico y el transporte básico funcionaban.

### `ChannelShell` mínimo

Se añadió `PRUEBA SHELL MÍNIMA`, también con sesión y canal nuevos. Usaba:

```kotlin
setPty(true)
setPtyType("xterm")
```

No usaba `setPtySize()`, parser ANSI ni emulador. Ejecutó:

```text
printf 'mini_shell_ok\n'; exit\n
```

con `exitStatus=0`, salida correcta y cierre normal. Por tanto, `ChannelShell` y PTY no eran el problema por sí mismos.

**Este fue el punto de inflexión de la investigación.**

Demostró que el transporte SSH, `ChannelShell`, el PTY y la comunicación bidireccional funcionaban correctamente cuando se utilizaba una implementación mínima. A partir de ese momento la investigación se centró exclusivamente en las diferencias entre dicha implementación y el flujo interactivo original.

## Hipótesis descartadas

Durante la investigación quedaron descartadas las siguientes hipótesis:

- contraseña incorrecta;
- listeners de conexión/desconexión;
- botón `ENVIAR`;
- terminadores `CR` y `LF`;
- ruta pública/NAT;
- servidor OpenSSH;
- configuración de Bash;
- autenticación SSH;
- `ChannelExec`;
- `ChannelShell` como mecanismo;
- AES-GCM;
- `aes128-ctr + hmac-sha2-256`;
- parser ANSI (como causa inicial);
- PTY como concepto general.

La investigación fue reduciendo progresivamente el problema hasta localizar una diferencia concreta en la implementación interactiva.

## Causa raíz

El flujo interactivo original llamaba a `ChannelShell.setPtySize()`:

1. antes de conectar el canal;
2. desde callbacks de layout/insets;
3. potencialmente varias veces al cambiar el tamaño de la interfaz o del teclado.

En esta implementación, las llamadas a `ChannelShell.setPtySize()` realizadas antes de completar la conexión del canal y posteriormente desde callbacks de layout provocaban una secuencia que terminaba corrompiendo la sesión SSH. Al eliminar dichas llamadas, el problema desapareció completamente. La evidencia apunta a la interacción entre `setPtySize()` y el ciclo de vida del canal en esta aplicación; el documento no concluye que `setPtySize()` sea defectuoso de forma general en JSch.

## Corrección aplicada

El flujo interactivo principal ahora:

```kotlin
shell.setPty(true)
shell.setPtyType("xterm")
```

Y no realiza ninguna llamada activa a `setPtySize()`.

También se eliminó el callback global de layout que actualizaba el tamaño del PTY. Se dejó un comentario junto a la configuración indicando por qué el dimensionado dinámico permanece desactivado.

El terminador del flujo interactivo se ajustó finalmente a `CR` (`0x0D`), porque los servidores Windows mostraban el comando al recibir `LF` (`0x0A`) pero no lo ejecutaban como Enter. Con `CR`, comandos como `dir` se ejecutan correctamente.

La configuración criptográfica temporal conservada es:

```text
cipher.c2s=aes128-ctr
cipher.s2c=aes128-ctr
mac.c2s=hmac-sha2-256
mac.s2c=hmac-sha2-256
compression.c2s=none
compression.s2c=none
```

No se habilitaron CBC, MD5 ni reconexión automática.

## Instrumentación añadida

Se añadieron logs para:

- acciones UI (`UI_ACTION send_button`, conexión y desconexión);
- identidad de sesión, canal y streams;
- negociación oficial de JSch;
- bytes enviados;
- `write_ok` y `flush_ok`;
- EOF y estados finales;
- excepciones con stack trace;
- pruebas `EXEC_TEST` y `MIN_SHELL_TEST`.

También se corrigió la prueba mínima para que el LF final fuese un byte real y no la secuencia literal `\\n`.

## Validación

### LAN

Con `192.168.1.115:22`, usando `xterm` y sin `setPtySize()`:

- `ls` funcionó;
- se recibió la salida completa;
- no hubo EOF inesperado;
- no hubo errores MAC;
- el botón permaneció como `DESCONECTAR TERMINAL`.

La secuencia de pruebas se inició con `pwd`, `ls`, `whoami`, `echo prueba` y `comando_inexistente`; la sesión permaneció conectada durante la prueba automatizada. La captura completa de `uname -a` no quedó finalizada por un timeout de automatización, aunque no se observó una nueva desconexión.

### Ruta pública y datos móviles

Se confirmó que el teléfono tenía Wi-Fi desactivado y transporte `CELLULAR` activo. La conexión pública alcanzó:

```text
barelrancho.es:1234
session connected
shell connected
read loop started
```

La validación pública se comprobó finalmente con el teléfono usando datos móviles y Wi-Fi desactivado. La conexión permaneció estable después de la corrección y no reapareció el EOF inesperado ni el error MAC. No se añaden aquí comandos o resultados individuales que no hayan quedado registrados de forma verificable.

### Servidor

El servidor usado fue `goldenshark`, Ubuntu con OpenSSH 9.6p1. La cuenta `ruben` usa `/bin/bash`. Las sesiones de las pruebas corregidas se abrieron y cerraron normalmente, sin mensajes MAC incorrectos.

## Compilación e instalación

Comando utilizado:

```powershell
.\gradlew.bat assembleDebug
```

Resultado:

```text
BUILD SUCCESSFUL
```

Instalación conservando datos:

```powershell
F:\tools\android-sdk\platform-tools\adb.exe install -r `
  "F:\PROYECTOS CODEX\mini-ssh\app\build\outputs\apk\debug\app-debug.apk"
```

Resultado:

```text
Success
```

No se ejecutaron `adb uninstall`, `pm clear` ni borrado de datos.

## Lecciones aprendidas

- No asumir que un EOF implica un problema de autenticación.
- Reducir el problema mediante implementaciones mínimas.
- Modificar una sola variable por experimento.
- Validar cada hipótesis con evidencia antes de descartarla.
- Comparar implementaciones funcionales frente a implementaciones defectuosas.
- Revisar tanto los registros del cliente como los del servidor.
- Documentar también las hipótesis descartadas.

## Estado final

La corrección estable es mantener el PTY en modo `xterm` y no modificar dinámicamente su tamaño. El problema original de corrupción del transporte SSH quedó reproducido, aislado y corregido mediante la eliminación de las llamadas a `setPtySize()`.

Los botones diagnósticos `PRUEBA EXEC (DIAGNÓSTICO)` y `PRUEBA SHELL MÍNIMA (DIAGNÓSTICO)` permanecen en la aplicación para futuras comprobaciones; pueden retirarse en una limpieza posterior junto con parte de la instrumentación temporal.
