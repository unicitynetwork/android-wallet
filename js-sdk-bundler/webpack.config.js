const path = require('path');
const webpack = require('webpack');

module.exports = [
  // First config: Bundle the SDK
  {
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

    // 6. Plugins: Additional functionality.
    plugins: [
    ],
  },
  // Second config: Compile the TypeScript wrapper
  {
    mode: 'production',
    entry: path.resolve(__dirname, '../app/src/main/assets/unicity-wrapper.ts'),
    output: {
      path: path.resolve(__dirname, '../app/src/main/assets'),
      filename: 'unicity-wrapper.js',
    },
    module: {
      rules: [
        {
          test: /\.tsx?$/,
          use: {
            loader: 'ts-loader',
            options: {
              configFile: path.resolve(__dirname, '../app/src/main/assets/tsconfig.json')
            }
          },
          exclude: /node_modules/,
        },
      ],
    },
    resolve: {
      extensions: ['.tsx', '.ts', '.js'],
    },
    // Mark unicity as external since it's loaded from unicity-sdk.js
    externals: {
      unicity: 'unicity'
    },
  },
  // Third config: Compile the test file
  {
    mode: 'production',
    entry: path.resolve(__dirname, '../app/src/main/assets/unicity-test.ts'),
    output: {
      path: path.resolve(__dirname, '../app/src/main/assets'),
      filename: 'unicity-test.js',
    },
    module: {
      rules: [
        {
          test: /\.tsx?$/,
          use: {
            loader: 'ts-loader',
            options: {
              configFile: path.resolve(__dirname, '../app/src/main/assets/tsconfig.json')
            }
          },
          exclude: /node_modules/,
        },
      ],
    },
    resolve: {
      extensions: ['.tsx', '.ts', '.js'],
    },
    // Mark unicity as external since it's loaded from unicity-sdk.js
    externals: {
      unicity: 'unicity'
    },
  },
];
