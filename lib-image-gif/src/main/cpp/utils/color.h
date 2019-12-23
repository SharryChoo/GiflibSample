//
// Created by Sharry on 2019-12-20.
//

#ifndef SCOMPRESSOR_COLOR_H
#define SCOMPRESSOR_COLOR_H

typedef uint32_t Color8888;
// TODO: handle endianness
static const Color8888 COLOR_8888_ALPHA_MASK = 0xff000000;
static const Color8888 TRANSPARENT = 0x0;

// TODO: handle endianness
#define ARGB_TO_COLOR8888(a, r, g, b) \
    ((a) << 24 | (b) << 16 | (g) << 8 | (r))

#endif //SCOMPRESSOR_COLOR_H
