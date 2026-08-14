# Ambi Projector Prototype v0.1

Primer prototipo Android para probar el concepto **cámara → análisis de los bordes del TV → halo proyectable** usando la cámara trasera de un celular.

## Qué hace esta versión

- Usa CameraX y la cámara trasera del teléfono.
- Analiza sólo pequeñas franjas del borde de un crop 16:9 central (CPU muy baja).
- 16 zonas arriba/abajo + 9 zonas izquierda/derecha.
- Smoothing temporal para evitar parpadeos.
- Renderiza un halo a pantalla completa con una máscara negra 16:9 en el centro.
- Incluye una capa independiente de texto contextual (demo), preparada para la futura información sobre lo que aparece en TV.
- Preview de cámara opcional para apuntar y depurar.

## Controles

- **Tap:** mostrar/ocultar preview de cámara.
- **Long press:** cambia el tamaño del crop que representa la TV (50% → 60% → ... → 90%).
- **Double tap:** muestra por 4 segundos un texto contextual de prueba.

Para la primera prueba, apuntá el teléfono hacia la TV y tratá de dejarla centrada. Con long-press ajustá el crop hasta que coincida aproximadamente con la TV.

## Compilar

1. Abrí el proyecto con Android Studio.
2. Esperá el Gradle Sync.
3. Conectá un celular Android (API 23+), habilitá USB debugging y ejecutá `app`.
4. Aceptá el permiso de cámara.

Configuración actual:

- compileSdk 36
- targetSdk 36
- minSdk 23
- CameraX 1.6.1
- Java 17
