# Mendel Java Code Challenge

Diseño de una API REST para guardar y reemplazar transacciones, consultar sus IDs por tipo y calcular el monto de una transacción junto con sus descendientes. La entrega prevista incluye Spring Boot, almacenamiento en memoria, tests de integración y ejecución con Docker. También incorpora paginación opcional por cursor en la búsqueda por tipo.

El proyecto está en la etapa de definición de diseño. Todavía no hay una aplicación ejecutable ni un contenedor.

## Documentación

- [Enunciado completo](docs/assigment.md).
- [Decisiones de diseño](docs/design-desicions.md).
- [Plan de pruebas y controles de calidad](docs/tests.md).

## Ejecución y pruebas

Los comandos para levantar el contenedor, ejecutar la aplicación y correr las pruebas se incorporarán cuando exista la implementación y se hayan verificado.

## Comportamiento acordado

Los errores usan un código estable y un mensaje específico en inglés. Los tipos se normalizan quitando espacios externos y convirtiéndolos a minúsculas. Las lecturas y escrituras se coordinan mediante un lock compartido de lectura/escritura: cada consulta observa un estado coherente y las escrituras son exclusivas, sin prometer orden entre requests concurrentes.

Los datos se mantienen en memoria y se pierden al reiniciar. Los montos y las sumas usan double conforme a la consigna, con sus limitaciones de precisión documentadas. Los contratos, ejemplos, alcance y preguntas abiertas se detallan en el registro de decisiones.

## Limitaciones y compatibilidad con el enunciado

Las siguientes limitaciones corresponden al diseño acordado; se verificarán al implementar. Algunas preservan requisitos explícitos del [enunciado](docs/assigment.md) y otras son decisiones de alcance documentadas en [Decisiones de diseño](docs/design-desicions.md).

- **Interpretación del dominio.** Modelamos movimientos editables, no asientos contables definitivos ni transacciones de base de datos. El cliente puede elegir cualquier ID entero dentro de `long`, incluido cero y negativos; los montos admiten cualquier signo dentro del rango finito de `double`. No agregamos restricciones de negocio que el enunciado no define. Más contexto podría justificar IDs positivos, límites de importes, movimientos no editables o correcciones auditables; esas reglas requerirían revisar el diseño.
- **Precisión numérica.** Se mantiene `double` para montos y sumas porque es el tipo especificado por la consigna. No se garantiza exactitud decimal: por ejemplo, `0.1 + 0.2` puede producir `0.30000000000000004`, y sumar un monto pequeño a uno grande puede perder precisión. Se usa un orden de acumulación fijo para obtener resultados reproducibles, sin redondeo adicional. Si un resultado intermedio desborda, la consulta devuelve `409 SUM_OVERFLOW`, sin infinito, NaN ni un cálculo alternativo del total. Con montos negativos, ese desbordamiento puede ocurrir aunque la suma matemática final pudiera ser finita. Para producción se evaluaría una representación decimal y rangos definidos por el negocio; esa evolución no se incorpora al contrato actual.
- **Respuesta completa sin parámetros.** La búsqueda por tipo devuelve todos los IDs cuando no se envían parámetros, respetando la consigna. La paginación opcional limita únicamente las consultas paginadas: no elimina el costo de memoria, copia y transmisión de una respuesta completa grande.
- **Persistencia y capacidad.** Se eligió almacenamiento en memoria en una única instancia, permitido por el enunciado y sin SQL. Los datos se pierden al reiniciar. La capacidad depende de los recursos disponibles; no se promete almacenamiento ilimitado ni una latencia o volumen determinados. El lock local no coordina varias instancias.
- **Concurrencia.** El lock de lectura/escritura prioriza la coherencia del estado: las escrituras son exclusivas y una lectura larga puede demorarlas. No se garantiza orden de llegada entre requests concurrentes; gana el último update aplicado. La inmutabilidad no elimina esta coordinación.
- **Fallos inesperados durante una escritura.** Un bug o falta de memoria puede interrumpir la actualización de los tres mapas y dejar un estado inconsistente. El lock no ofrece rollback. No contamos con un motor de base de datos transaccional ni implementamos recuperación propia en memoria; aceptamos esta vulnerabilidad dentro del alcance del challenge. Reiniciar pierde los datos, no restaura el último estado válido. Los rechazos de negocio previstos se validan antes de modificar y no deben dejar cambios parciales.
- **Cambios entre páginas.** Cada página observa un estado coherente, pero no se conserva un snapshot durante toda la navegación. Altas y cambios de tipo pueden modificar los resultados entre peticiones; un ID incorporado por debajo de la posición del cursor no aparecerá en las páginas siguientes.
- **Alcance de la entrega.** Versioning, autenticación, rate limiting, interfaz visual, borrado e historial de cambios quedan fuera por decisión de alcance, no por prohibición de la consigna. El diseño no pretende cubrir todos los requisitos de un servicio de producción.


