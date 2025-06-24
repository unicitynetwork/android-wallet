const path = require('path');

module.exports = {
  // 1. Mode: 'production' creates an optimized, smaller file.
  mode: 'production',

  // 2. Entry Point: Webpack starts bundling from this file.
  entry: './src/index.ts',

  // 3. Output Configuration: Where to save the final bundle.
  output: {
    // CRITICAL: This path points directly into your Android app's assets folder.
    path: path.resolve(__dirname, '../app/src/main/assets'),

    // The name of the final JavaScript file.
    filename: 'unicity-sdk.js',

    // CRITICAL: This exposes the bundled code as a global variable.
    // Your WebView will be able to access the SDK via `window.unicity`.
    library: {
      name: 'unicity',
      type: 'umd', // Universal Module Definition - makes it work everywhere.
    },
  },

  // 4. Module Rules: How to handle different file types.
  module: {
    rules: [
      {
        test: /\.tsx?$/, // A regular expression for .ts and .tsx files.
        use: 'ts-loader', // Use the ts-loader to compile them.
        exclude: /node_modules/, // Important for performance.
      },
    ],
  },

  // 5. Resolve Extensions: Helps Webpack find imported files.
  resolve: {
    extensions: ['.tsx', '.ts', '.js'],
  },
};
