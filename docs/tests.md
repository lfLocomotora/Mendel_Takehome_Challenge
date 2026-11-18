# Pruebas y controles de calidad

Plan para verificar el [diseño acordado](design-desicions.md) y el [contrato](assigment.md). No hay código ni herramientas configuradas todavía: este documento define qué implementaremos y cómo comprobaremos sus garantías.

La propuesta de herramientas asume Java y Maven. Usar las últimas versiones estables compatibles entre sí, verificadas en fuentes oficiales al configurar o actualizar el proyecto. Fijar las versiones resueltas para reproducir el build; no usar versiones dinámicas, snapshots ni prereleases. Si hay un conflicto de compatibilidad o una versión gestionada por Spring Boot difiere de la última estable, explicarlo antes de elegir una excepción.

## Política de dependencias

| Regla | Criterio |
| --- | --- |
| Valor concreto | Cada dependencia debe resolver una necesidad actual del contrato, tests o calidad. |
| Simplicidad | Preferir el JDK o las herramientas existentes cuando cubran la necesidad. |
| Sin duplicación | No agregar dos librerías o plugins que hagan lo mismo. |
| Revisión | Avisar si una dependencia propuesta o existente no aporta valor y proponer quitarla. |
| Versiones | Últimas estables compatibles, fijadas y verificadas; documentar cualquier excepción. |

No instalar herramientas solo porque aparecen en este plan. En particular, conservar Checkstyle únicamente si aporta reglas distintas del formato de Spotless, y ArchUnit si implementamos las restricciones arquitectónicas acordadas.

## Desarrollo guiado por tests

Seguimos TDD: cada comportamiento nuevo comienza con un test. Este plan es el catálogo de casos; no escribiremos toda la suite de una vez antes de implementar.

| Paso | Acción |
| --- | --- |
| Rojo | Elegir un caso, escribir su test y ejecutarlo. Debe fallar por el comportamiento faltante, no por un problema accidental de configuración. |
| Verde | Implementar lo mínimo para que pase, sin romper los tests existentes. |
| Refactorizar | Mejorar nombres, responsabilidades y estructura; volver a ejecutar los tests relevantes y controles de calidad configurados. |
| Continuar | Agregar el siguiente caso y conservar los anteriores como regresión. |

Primero acordamos el resultado esperado según el contrato. No se modifica un test correcto para acomodar una implementación incorrecta; si cambia una regla, se actualizan explícitamente diseño y tests.

Empezaremos por guardar y consultar una transacción, luego reemplazos, relaciones y sumas, validaciones, paginación y concurrencia. Cada comportamiento HTTP tendrá cobertura de integración; sus reglas complejas se desarrollarán con tests más pequeños. El arranque del proyecto y su configuración necesitan una verificación inicial antes del primer ciclo funcional.

Los tests verifican comportamiento, no una copia del algoritmo. El código se validará con la suite acumulada; se añadirá cobertura ante casos nuevos o defectos encontrados. Si usamos commits incrementales, cada cambio terminado debe quedar en verde y explicar el comportamiento agregado.

## Estrategia

| Nivel | Qué verifica | Cómo |
| --- | --- | --- |
| Reglas | Validaciones, suma y normalización. | JUnit Jupiter, casos parametrizados, sin iniciar Spring. |
| Almacenamiento | Coherencia de los tres mapas y copias independientes. | Implementación real en memoria, datos aislados. |
| Integración | HTTP → controller → servicio → almacenamiento → respuesta. | Spring Boot con servidor en puerto aleatorio y cliente HTTP real. |
| Concurrencia | Alcance del lock y ausencia de intercalaciones inválidas. | Lock real, threads controlados y sincronización explícita. |
| Calidad | Formato, reglas estáticas y dependencias entre componentes. | Controles automáticos en la verificación del proyecto. |
| Contenedor | La imagen construida sirve la API y reinicia vacía. | Prueba de humo contra Docker. |

No simularemos el servicio ni el almacenamiento en los tests que demuestran la integración completa. Las reglas complejas se prueban aisladamente; la integración comprueba su traducción HTTP sin repetir todas las combinaciones.

## Preparación y aislamiento

| Regla | Aplicación |
| --- | --- |
| Estado limpio | Un almacenamiento nuevo por test de reglas; aislamiento explícito entre escenarios HTTP. |
| Sin dependencias entre tests | Cada caso prepara sus datos; no depende del orden de ejecución. |
| Datos reproducibles | Fixtures pequeñas; semillas fijas si generamos operaciones. |
| Variantes | Parametrizar signos, límites y entradas inválidas. |
| Aserciones | Comprobar resultados y estado posterior, no solo el código HTTP. |
| Recursos | Cerrar executors, esperar futures y liberar barreras en finally. |
| Timeouts | Acotar esperas para detectar bloqueos; no tratarlos como un SLA de rendimiento. |

Como no hay base de datos, una transacción de test no limpiará nuestro almacenamiento en memoria. No agregaremos un endpoint público de reset: el aislamiento se resolverá en la configuración o fixtures de pruebas.

## Creación y reemplazo

En estos casos comprobamos respuesta HTTP y efectos observables en búsquedas y sumas.

| Caso | Resultado esperado |
| --- | --- |
| Crear sin padre | 201 y `{"status":"ok"}`; suma igual al monto propio. |
| Crear con padre existente | 201; aparece en los descendientes del padre. |
| Repetir el mismo PUT | 200; una transacción, sin IDs ni montos duplicados. |
| Reemplazar monto | 200; cambia la suma propia y de antecesores. |
| Reemplazar tipo | Deja de aparecer en el tipo anterior y aparece una vez en el nuevo. |
| Reemplazar padre | Sale de la rama anterior y entra en la nueva. |
| Mover una transacción con hijos | Conserva sus hijos; toda la rama cambia de antecesores. |
| Omitir padre al reemplazar | Queda sin padre; sus hijos permanecen vinculados. |
| Enviar padre null | Mismo comportamiento que omitirlo. |
| Cambiar monto, tipo y padre juntos | Los tres mapas y todas las consultas quedan coherentes. |

Para cada rechazo por validación, capturar el estado previo, intentar el cambio y comprobar que se conserva. Incluir cambios simultáneos de monto y tipo junto con el padre inválido: ninguno debe aplicarse.

## Datos y normalización

Combinar tests parametrizados de reglas con casos HTTP que ejerciten el parser real.

| Caso | Resultado esperado |
| --- | --- |
| IDs negativos, cero y positivos | Aceptados como ID y como padre existente. |
| Long.MIN_VALUE y Long.MAX_VALUE | Se conservan exactamente, sin pasar por double. |
| ID fuera de long o con fracción | 400; sin truncamiento ni cambios. |
| Monto negativo, cero o positivo | Aceptado; el negativo resta. |
| Double.MAX_VALUE y su negativo | Entrada válida si la suma consultada no desborda. |
| Monto ausente, null o de tipo incorrecto | 400; no confundir ausencia con cero. |
| Monto fuera de rango finito | 400. |
| NaN o infinito | Rechazados; probar tanto reglas internas como entrada HTTP inválida. |
| Tipo cars, Cars y espacios externos | Misma categoría normalizada cars. |
| Tipo ausente, null, vacío o solo espacios | 400 con mensaje específico. |
| Tipo de dato incorrecto o campo desconocido | 400; indicar la causa en inglés. |
| JSON mal formado o cuerpo vacío | 400 con el formato común de error. |

No forzar una interpretación de ajuste o devolución: los tests de negativos verifican aritmética, no un negocio que no definimos. La conversión de strings numéricos y la política de espacios Unicode requieren precisar el parser; no se aceptarán coerciones silenciosas por accidente.

## Jerarquía y ciclos

| Caso | Resultado esperado |
| --- | --- |
| Padre inexistente al crear | 409 PARENT_NOT_FOUND; no crear el hijo. |
| Padre inexistente al actualizar | 409; conservar el estado previo. |
| Padre igual al propio ID | 409 CYCLE_DETECTED, incluso al crear. |
| Ciclo de dos transacciones | Rechazar el cambio que lo cierra. |
| Ciclo indirecto de varios niveles | Rechazar; no revisar solo el padre inmediato. |
| Poner un descendiente como padre | Rechazar sin cambiar monto, tipo ni asociaciones. |
| Mantener el padre actual | Update válido, sin duplicar asociaciones. |
| Mover a una rama independiente | Update válido; jerarquía coherente. |

## Consultas y suma

| Caso | Resultado esperado |
| --- | --- |
| Ejemplo original: 10 → 11 → 12 | sum(10)=20000 y sum(11)=15000. |
| Varias ramas y tipos | Incluir todos los descendientes una vez, sin filtrar por tipo. |
| Hoja | Devuelve su propio monto. |
| Consultar nodo intermedio | No incluye antecesores, hermanos ni otra raíz. |
| Montos positivos, negativos y cero | Suma algebraica, incluido un total cero. |
| ID inexistente | 404 TRANSACTION_NOT_FOUND; distinto de total cero. |
| Cadena profunda | Finaliza sin depender de la pila de llamadas; fixture de miles de nodos. |
| Mismo estado, distinta inserción válida | Mismo resultado: recorrido fijo en profundidad con hijos ascendentes. |
| 0.1 seguido de 0.2 | Resultado double 0.30000000000000004; sin redondear a 0.3. |
| Desbordamiento positivo o negativo | 409 SUM_OVERFLOW con mensaje acordado. |
| Desbordamiento intermedio con compensación posterior | Error igualmente; no calcular un total alternativo. |

Para demostrar el orden de acumulación, usar una raíz con monto 0 y tres hijos ascendentes con montos `1e16`, `-1e16` y `1`: el resultado secuencial esperado es `1`. Cambiar el orden de inserción no debe alterarlo.

Con montos enteros pequeños, comparar exactamente. Para fixtures double específicas, comprobar el valor double esperado; una tolerancia amplia ocultaría el redondeo indebido. En HTTP comparar el número parseado, sin exigir que 2 y 2.0 tengan la misma representación textual.

## Búsqueda por tipo y paginación

### Búsqueda completa

| Caso | Resultado esperado |
| --- | --- |
| Tipo normalizado al consultar | Coincide con la normalización usada al guardar. |
| Tipo sin coincidencias | 200 y []. |
| Sin parámetros | Todos los IDs, sin exigir orden ni permitir duplicados. |
| Cambios de tipo | No quedan asociaciones antiguas. |

### Páginas y parámetros

| Caso | Resultado esperado |
| --- | --- |
| Solo limit | Primera página ascendente, de hasta limit IDs. |
| Limit y cursor | Continuar con IDs mayores al último. |
| Solo cursor | Hasta 100 resultados; preparar más de 100 para verificarlo. |
| Limit 1 y 1000 | Ambos válidos. |
| Limit 0, negativo, fraccionario, no numérico o mayor a 1000 | 400 con causa específica. |
| Primera, intermedia y última página | Sin omisiones ni duplicados si el conjunto no cambia. |
| Cantidad múltiplo exacto de limit | La última página llena no ofrece un next innecesario. |
| Lista vacía o cursor sin resultados posteriores | [] y sin Link next. |
| IDs negativos, cero y extremos de long | Orden numérico correcto, sin desbordar al continuar. |
| Cursor inválido o de otro tipo | 400 INVALID_CURSOR; mensajes diferentes en inglés. |

Seguir el Link devuelto por la API, sin reconstruir ni decodificar el token en el test HTTP. Comprobar que conserva el tipo, tamaño y continuación correctos, incluso si la URL necesita encoding.

### Cambios entre páginas

| Caso | Resultado esperado |
| --- | --- |
| El último ID cambia de tipo | El cursor sigue siendo válido. |
| Se agrega un ID inferior al cursor | No aparece en páginas posteriores. |
| Se agrega uno superior al cursor | Puede aparecer al consultar la siguiente página. |
| Un ID posterior deja el tipo | No aparece en la siguiente página. |
| Se repite un cursor tras un cambio | No exigir una fotografía del resultado anterior. |

No exigiremos consistencia de snapshot que el contrato no promete. Cada página sí debe corresponder a una lectura coherente.

## Almacenamiento y encapsulación

| Caso | Cómo verificarlo |
| --- | --- |
| Coherencia de los tres mapas | Tras crear, reemplazar o rechazar, comparar consultas con las relaciones esperadas. |
| IDs en mapas auxiliares | Ningún ID inexistente, duplicado o asociado al tipo/padre incorrecto. |
| Transaction inmutable | Conservar la referencia anterior; un reemplazo no modifica sus datos. |
| Copia de IDs por tipo e hijos | Alterar la colección obtenida no altera las consultas siguientes. Se admite copia modificable o inmodificable. |
| Copia independiente | Un cambio posterior del almacenamiento no modifica una colección ya obtenida. |
| Reinicio | Una nueva instancia no recupera transacciones previas. |

Podemos agregar secuencias de operaciones con semilla fija y montos enteros pequeños. Un modelo de referencia sencillo, basado en escanear registros y seguir padres, permite comprobar resultados sin copiar la lógica optimizada de los mapas auxiliares.

## Concurrencia reproducible

Usar el coordinador y el lock reales. Un decorador de almacenamiento de test permite detener una operación en puntos concretos y registrar accesos protegidos, sin agregar endpoints ni sleeps a producción.

CountDownLatch o barreras coordinan las fases. Un test que simplemente inicia dos threads no demuestra que se haya producido la intercalación buscada.

| Escenario | Coordinación y resultado |
| --- | --- |
| Dos lectores | Ambos entran a su sección de lectura antes de liberar al primero. |
| Lector activo y escritor | El lector conserva el acceso compartido; la escritura no accede al almacenamiento hasta que termine. |
| Escritor activo y lector | Pausar la escritura protegida; la lectura no accede al estado parcial. |
| Dos escritores | Sus secciones protegidas nunca se superponen, incluso con IDs distintos. |
| Dos padres que cerrarían un ciclo | Ambos piden el cambio; uno aplica y el otro devuelve 409. La jerarquía final sigue válida. |
| Mismo ID creado a la vez | Un 201 y un 200, sin duplicados; estado final de uno de los reemplazos completos. |
| Dos updates del mismo ID | Con orden de aplicación controlado, queda el último aplicado. No asumir orden por inicio del thread. |
| Cambio de tipo concurrente | Al terminar, el ID solo aparece en su categoría actual. |
| Rechazo por validación | Libera el lock; una operación posterior puede completarse. |

Para el coordinador, comprobar exclusión con adquisición no bloqueante desde otro thread. Para el servicio, un almacenamiento instrumentado verifica que la validación dependiente del estado y todas las escrituras ocurren bajo el mismo lock exclusivo. Así evitamos depender solo de un timeout para afirmar que un thread estaba bloqueado.

### Regresión de los dos casos documentados

Estado inicial: raíces 10 y 20 con monto 0; transacción 1 con monto 2 y padre 10. El PUT cambia la 1 a monto 3 y padre 20.

| Caso | Qué forzar | Resultado |
| --- | --- | --- |
| Escritura parcial | Pausar tras reemplazar el objeto y antes de actualizar mapas auxiliares. | El lector entra después y devuelve sum(10)=0, nunca 3. |
| Lectura en curso | Pausar después de obtener el hijo 1 y antes de leer su monto. | El escritor espera; el lector devuelve 2, nunca 3. |

Los puntos de pausa pertenecen al soporte de test. Liberar siempre sus barreras y comprobar las excepciones de los futures. No usar Thread.sleep para decidir quién obtiene primero el lock, ni reintentar un test fallido hasta que pase.

Estas pruebas verifican escenarios e invariantes, no todas las planificaciones posibles del SO. La ausencia de orden de llegada y de equidad garantizada no es un fallo del contrato actual.

## Formato, lint y arquitectura

Son controles distintos: el formato unifica la apariencia, el lint detecta incumplimientos de reglas de código y los tests de arquitectura protegen la separación de responsabilidades. Las herramientas todavía no están configuradas.

| Control | Herramienta | Ejemplo |
| --- | --- | --- |
| Formato | Spotless + google-java-format | Aplicar siempre la misma indentación, espacios y saltos de línea en Java. |
| Lint | Checkstyle | Rechazar imports wildcard, nombres fuera de la convención y bloques catch vacíos. |
| Arquitectura | ArchUnit | Fallar si un controller accede directamente al almacenamiento o el servicio depende de HTTP. |

Spotless puede corregir el formato automáticamente; su modo de verificación solo informa incumplimientos. Checkstyle señala las reglas violadas y ArchUnit ejecuta tests sobre las dependencias entre clases y paquetes. No duplicaremos en Checkstyle las reglas de espacios que ya resuelve el formatter.

EditorConfig complementa estos controles configurando UTF-8, saltos LF, newline final y espacios sin tabs en editores compatibles; no sustituye la verificación del proyecto. Se elimina el formateador adicional de documentación para mantener una sola cadena de herramientas Java/Maven.

Las reglas arquitectónicas deben permitir servicio → coordinador/almacenamiento y exception handler → errores de negocio. No convertir una regla genérica de capas en una restricción que contradiga el diseño.

Fijar versiones y configuración en el repositorio. Local y CI deben ejecutar las mismas verificaciones y fallar ante incumplimientos; no depender del editor de cada persona.

Formatear no prueba correctitud ni extensibilidad. Estas reglas aportan consistencia y límites de dependencia; la revisión debe seguir evaluando responsabilidades, nombres y claridad. No agregamos límites arbitrarios de líneas por clase ni porcentajes de cobertura como sustituto de buen diseño.

## Ejecución y verificación de entrega

Flujo propuesto: comprobar formato y lint, compilar, ejecutar reglas y arquitectura, integración HTTP y, por último, construir y probar el contenedor. Una violación debe hacer fallar la verificación.

| Acción | Resultado requerido |
| --- | --- |
| Verificar | No modifica archivos; falla ante formato, lint o tests incorrectos. |
| Formatear | Acción explícita que corrige archivos; ejecutarla dos veces no introduce nuevos cambios. |
| Tests rápidos | Reglas, almacenamiento, arquitectura y coordinación. |
| Integración | Aplicación real en puerto aleatorio, sin mockear la lógica principal. |
| Docker | Construir imagen, esperar disponibilidad con plazo acotado y ejecutar un flujo crear → buscar → sumar → paginar. |
| Reinicio del contenedor | Datos anteriores ausentes; no hay persistencia prometida. |

Con Maven, Surefire puede ejecutar `*Test` y Failsafe `*IT`, integrados en la fase verify. El comando único, el wrapper y los controles de calidad se agregarán al configurar el proyecto. Hoy no existen: no presentamos comandos propuestos como comprobados.

Los tests funcionales no usan umbrales de milisegundos ni agotan memoria para buscar límites. Las cadenas profundas demuestran recorrido iterativo, no una capacidad ilimitada.

## Fuera de esta cobertura

| Tema | Motivo |
| --- | --- |
| Rollback ante OOM o bugs durante una escritura | No existe recuperación transaccional propia; limitación documentada. |
| Orden de llegada entre requests | No se garantiza. |
| Snapshot entre páginas | No se garantiza. |
| Varias instancias, autenticación y rate limiting | Fuera del alcance actual. |
| Carga y capacidad de producción | Requieren objetivos y mediciones separados. |

## Referencias de herramientas

- [JUnit: tests parametrizados](https://docs.junit.org/current/writing-tests/parameterized-classes-and-tests.html).
- [Spring Boot: integración con servidor real](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html).
- [CountDownLatch: coordinación de threads](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/CountDownLatch.html).
- [Spotless para Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven).
- [Checkstyle: reglas configurables](https://checkstyle.org/).
- [ArchUnit: reglas de dependencias](https://www.archunit.org/userguide/html/000_Index.html).
- [Maven Failsafe: tests de integración](https://maven.apache.org/surefire/maven-failsafe-plugin/).

