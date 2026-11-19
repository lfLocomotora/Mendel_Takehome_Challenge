# Mendel Java Code Challenge

API REST con Spring Boot para guardar y reemplazar movimientos, consultar sus IDs por tipo y sumar un movimiento junto con sus descendientes. Implementa el [enunciado completo](docs/assigment.md) con almacenamiento en memoria, sin SQL, e incorpora paginación opcional por cursor.

La entrega incluye aplicación ejecutable, tests unitarios y de integración HTTP, controles de calidad y contenedor Docker. El desarrollo usa ciclos TDD y commits incrementales.

## Documentación

- [Enunciado completo](docs/assigment.md).
- [Decisiones de diseño](docs/design-desicions.md).
- [Plan de pruebas y controles de calidad](docs/tests.md).

## Ejecución y pruebas

### Con Docker

Requiere Docker con BuildKit (incluido en Docker Desktop). No necesita Java ni Maven en la máquina local. La construcción ejecuta todos los tests, lint y formato antes de generar la imagen; la aplicación corre con un usuario sin privilegios.

```sh
docker build -t mendel-transactions:local .
docker run --rm --name mendel-transactions -p 127.0.0.1:8080:8080 mendel-transactions:local
```

Desde otra terminal, ejecutar la prueba de humo. Requiere `curl` y herramientas estándar de shell; crea y elimina su propio contenedor con un puerto libre.

```sh
sh scripts/smoke-test.sh
```

### Con Java

Requiere JDK 27 y `JAVA_HOME` configurado. El wrapper descarga Maven: no hace falta instalarlo. La primera ejecución necesita acceso a los repositorios de dependencias.

```sh
./mvnw verify
./mvnw spring-boot:run
```

La API escucha en `http://localhost:8080`. Para ejecutar el JAR construido:

```sh
java -jar target/transaction-service-1.0.0.jar
```

### Controles de calidad

| Comando | Qué hace |
| --- | --- |
| `./mvnw clean verify` | Compila, verifica Checkstyle y Spotless, ejecuta tests unitarios, de arquitectura e integración HTTP y genera el JAR. |
| `./mvnw test` | Ejecuta tests de reglas, almacenamiento, coordinación, arquitectura y arranque. No ejecuta `*IT` ni el control de formato de `verify`. |
| `./mvnw spotless:apply` | Corrige explícitamente el formato Java. |
| `sh scripts/smoke-test.sh` | Verifica la imagen construida: API, paginación, errores, reemplazos y reinicio vacío. |

Las pruebas HTTP usan servidor real en puerto aleatorio. Los tests de concurrencia usan locks y sincronización explícita, sin depender de sleeps. La [configuración de CI](.github/workflows/verify.yml) construye la imagen con las mismas verificaciones y ejecuta la prueba de humo.

## Uso de la API

```sh
curl -i -X PUT http://localhost:8080/transactions/10 \
  -H 'Content-Type: application/json' -d '{"amount":5000,"type":"cars"}'
curl -i -X PUT http://localhost:8080/transactions/11 \
  -H 'Content-Type: application/json' -d '{"amount":10000,"type":"shopping","parent_id":10}'
curl -i -X PUT http://localhost:8080/transactions/12 \
  -H 'Content-Type: application/json' -d '{"amount":5000,"type":"shopping","parent_id":11}'
curl http://localhost:8080/transactions/types/cars
curl http://localhost:8080/transactions/sum/10
curl http://localhost:8080/transactions/sum/11
```

Las consultas devuelven `[10]`, `{"sum":20000.0}` y `{"sum":15000.0}`. Un PUT nuevo responde `201`; un reemplazo, incluso idéntico, responde `200`. Ambos devuelven `{"status":"ok"}`. Omitir `parent_id` o enviarlo como `null` elimina la relación con el padre, conservando los hijos.

Para paginar, enviar `limit` entre 1 y 1000:

```sh
curl -i 'http://localhost:8080/transactions/types/shopping?limit=1'
```

El cuerpo sigue siendo un array: `[11]`. Si quedan IDs, el header `Link: <...>; rel="next"` contiene una URL relativa que se puede seguir sobre el mismo servidor. La continuación usa IDs ascendentes y conserva el tipo; el cursor es opaco para el cliente. Solo `cursor` usa tamaño 100. Sin parámetros se devuelve la lista completa. Parámetros desconocidos o repetidos se rechazan.

El tipo ocupa un segmento de URL: sus caracteres reservados deben codificarse. Por ejemplo, un movimiento de tipo `food/drink` se consulta con `/transactions/types/food%2Fdrink`. Los enlaces de paginación ya incluyen esa codificación. `Cars` y `cars` pertenecen a la misma categoría por decisión de diseño: consultar cualquiera devuelve los IDs de ambas variantes.

Los errores esperados tienen este formato y mensajes en inglés:

```json
{"code":"PARENT_NOT_FOUND","message":"Parent transaction with ID 99 does not exist."}
```

| HTTP | Código | Causa |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON, campos, ID, monto, tipo o parámetros inválidos. |
| 400 | `INVALID_CURSOR` | Cursor inválido o de otro tipo. |
| 404 | `TRANSACTION_NOT_FOUND` | No existe el ID cuya suma se consulta. |
| 409 | `PARENT_NOT_FOUND` | No existe el padre solicitado. |
| 409 | `CYCLE_DETECTED` | La relación produciría un ciclo. |
| 409 | `SUM_OVERFLOW` | Una suma intermedia excede el rango finito de double. |

## Estructura y dependencias

| Paquete | Responsabilidad |
| --- | --- |
| `api` | Rutas, interpretación estricta de JSON y respuestas HTTP. |
| `application` | Reglas, suma, paginación y coordinación de operaciones. |
| `domain` | Movimiento inmutable y errores de negocio. |
| `storage` | Tres mapas: movimientos por ID, IDs por tipo e IDs de hijos por padre. |
| `concurrency` | Lock compartido de lectura/escritura. |

La configuración de Spring conecta estos componentes. El controller no accede a los mapas; el almacenamiento no conoce HTTP. Los diagramas y tradeoffs están en las [decisiones de diseño](docs/design-desicions.md).

| Herramienta | Versión fijada |
| --- | --- |
| Java / Temurin en Docker | 27 / 27.0.1.0.2 |
| Spring Boot | 4.1.2 |
| Maven Wrapper / Maven | 3.3.4 / 3.10.1 |
| JUnit Jupiter | 6.1.3 |
| Surefire / Failsafe | 3.6.1 |
| Spotless / google-java-format | 3.11.0 / 2.0.0 |
| Plugin Checkstyle / motor Checkstyle | 3.6.0 / 14.4.0 |
| ArchUnit | 1.5.1 |

Las versiones estables se verificaron al configurar el proyecto. Spring Boot gestiona las dependencias de ejecución compatibles y sus versiones transitivas; las imágenes base se fijan por digest. JUnit tiene una versión explícita. La única dependencia funcional declarada es el starter web; las demás sirven para pruebas y calidad. No se agregan base de datos, Lombok, Mockito, Prettier ni un framework adicional de validación.

## Comportamiento acordado

Los errores usan un código estable y un mensaje específico en inglés. Los tipos se normalizan quitando espacios externos y convirtiéndolos a minúsculas. Las lecturas y escrituras se coordinan mediante un lock compartido de lectura/escritura: cada consulta observa un estado coherente y las escrituras son exclusivas, sin prometer orden entre requests concurrentes.

Los datos se mantienen en memoria y se pierden al reiniciar. Los montos y las sumas usan double conforme a la consigna, con sus limitaciones de precisión documentadas. Los contratos, ejemplos y alcance se detallan en el registro de decisiones.

## Limitaciones y compatibilidad con el enunciado

Algunas limitaciones preservan requisitos explícitos del [enunciado](docs/assigment.md); otras son decisiones de alcance documentadas en [Decisiones de diseño](docs/design-desicions.md).

- **Interpretación del dominio.** Modelamos movimientos editables, no asientos contables definitivos ni transacciones de base de datos. El cliente puede elegir cualquier ID entero dentro de `long`, incluido cero y negativos; los montos admiten cualquier signo dentro del rango finito de `double`. No agregamos restricciones de negocio que el enunciado no define. Más contexto podría justificar IDs positivos, límites de importes, movimientos no editables o correcciones auditables; esas reglas requerirían revisar el diseño.
- **Precisión numérica.** Se mantiene `double` para montos y sumas porque es el tipo especificado por la consigna. No se garantiza exactitud decimal: por ejemplo, `0.1 + 0.2` puede producir `0.30000000000000004`, y sumar un monto pequeño a uno grande puede perder precisión. Se usa un orden de acumulación fijo para obtener resultados reproducibles, sin redondeo adicional. Si un resultado intermedio desborda, la consulta devuelve `409 SUM_OVERFLOW`, sin infinito, NaN ni un cálculo alternativo del total. Con montos negativos, ese desbordamiento puede ocurrir aunque la suma matemática final pudiera ser finita. Para producción se evaluaría una representación decimal y rangos definidos por el negocio; esa evolución no se incorpora al contrato actual.
- **Respuesta completa sin parámetros.** La búsqueda por tipo devuelve todos los IDs cuando no se envían parámetros, respetando la consigna. La paginación opcional limita únicamente las consultas paginadas: no elimina el costo de memoria, copia y transmisión de una respuesta completa grande.
- **Persistencia y capacidad.** Se eligió almacenamiento en memoria en una única instancia, permitido por el enunciado y sin SQL. Los datos se pierden al reiniciar. La capacidad depende de los recursos disponibles; no se promete almacenamiento ilimitado ni una latencia o volumen determinados. El lock local no coordina varias instancias.
- **Concurrencia.** El lock de lectura/escritura prioriza la coherencia del estado: las escrituras son exclusivas y una lectura larga puede demorarlas. No se garantiza orden de llegada entre requests concurrentes; gana el último update aplicado. La inmutabilidad no elimina esta coordinación.
- **Fallos inesperados durante una escritura.** Un bug o falta de memoria puede interrumpir la actualización de los tres mapas y dejar un estado inconsistente. El lock no ofrece rollback. No contamos con un motor de base de datos transaccional ni implementamos recuperación propia en memoria; aceptamos esta vulnerabilidad dentro del alcance del challenge. Reiniciar pierde los datos, no restaura el último estado válido. Los rechazos de negocio previstos se validan antes de modificar y no deben dejar cambios parciales.
- **Cambios entre páginas.** Cada página observa un estado coherente, pero no se conserva un snapshot durante toda la navegación. Altas y cambios de tipo pueden modificar los resultados entre peticiones; un ID incorporado por debajo de la posición del cursor no aparecerá en las páginas siguientes.
- **Alcance de la entrega.** Versioning, autenticación, rate limiting, interfaz visual, borrado e historial de cambios quedan fuera por decisión de alcance, no por prohibición de la consigna. El diseño no pretende cubrir todos los requisitos de un servicio de producción.


