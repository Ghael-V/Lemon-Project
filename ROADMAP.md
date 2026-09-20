# Roadmap

Ideas we've discussed but deliberately parked — not started, not scheduled, just kept
so we don't lose track of them.

## Savestate real (multi-hilo)

El MVP actual (mismo-sesión, UI oculta) funciona pero no sobrevive a cerrar el juego, y
falla con juegos multi-hilo reales porque no hay forma de reconstruir el estado de
objetos del kernel (hilos, sesiones IPC, temporizadores) — ahora mismo no se serializa
nada de eso. No es un bug, es una funcionalidad nueva a nivel de kernel. Antes de meterle
mano en serio, toca investigar a fondo el alcance real.

## Frases random en la pantalla de carga

Ahora mismo la pantalla de carga solo dice "Cargando...". Añadir un pool de frases
tontas/curiosas que roten en cada carga, al estilo tips de carga de otros juegos.
Divertido, cero tracking nuevo, pero bajo impacto real.

## Icono alternativo desbloqueable

Usando el tiempo total jugado que ya calculamos en Estadísticas, desbloquear un icono
de la app alternativo como "logro" secreto al superar un umbral (ej. 10h acumuladas).
Requiere un `activity-alias`. Divertido, pero tampoco aporta demasiado por sí solo.

## Exportar/importar configuración (favoritos, estadísticas, ajustes por juego)

Favoritos, estadísticas de uso y ajustes por juego viven solo en `SharedPreferences`:
invisibles y no exportables. Un reinstall o cambio de móvil los pierde sin aviso — a
diferencia del backup de partidas guardadas (ya descartado por ser accesible a mano vía
"abrir carpeta de Lemon"), esto no tiene ninguna vía de recuperación hoy. Reutilizaría el
mismo mecanismo de Storage Access Framework que ya se usa para import/export de saves en
`GamePropertiesFragment`, empaquetando todo en un único archivo. Protege contra una
pérdida de datos real, no es solo un añadido cosmético.
