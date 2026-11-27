const ionic = require('@ionic/eslint-config/recommended');

module.exports = [
  { ignores: ['build', 'dist', 'example-app', 'docs', 'scripts/configure-dependencies.mjs'] },
  ...ionic,
];
