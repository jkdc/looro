import { register } from 'node:module';

register(new URL('./main-wiring-loader.mjs', import.meta.url));
