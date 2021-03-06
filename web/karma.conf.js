module.exports = function (config) {
  config.set({

    basePath: '',

    files: [
      'bower_components/angular/angular.js',
      'bower_components/angular-mocks/angular-mocks.js',
      'bower_components/angular-resource/angular-resource.js',
      'bower_components/angular-route/angular-route.js',
      'bower_components/angular-ui-router/release/angular-ui-router.js',

      'src/stratio-ui/script/ui.stratio.js',
      'src/stratio-ui/script/helper/*.js',
      'src/stratio-ui/script/layout/*.js',
      'src/stratio-ui/script/components/*.js',

      'src/scripts/app.js',
      'src/scripts/vendors/*.js',
      'src/scripts/constants/**/*.js',
      'src/scripts/controllers/**/*.js',
      'src/scripts/services/**/*.js',
      'src/scripts/directives/**/*.js',
      'src/languages/en-US.json',
      'src/scripts/filters/truncate-number.js',

      // fixtures
      'test/mock/*.json',
      'test/**/**/*.js'
    ],

    autoWatch: false,

    reporters: ['junit', 'coverage', 'progress'],

    coverageReporter: {
      type: 'lcov',
      dir: 'target/coverage/'
    },
    frameworks: ['jasmine-jquery', 'jasmine'],

    browsers: ['PhantomJS'],
    port: 8080,

    preprocessors: {
      '**/*.json': ['ng-json2js'],
      'src/scripts/constants/**/*.js': ['coverage'],
      'src/scripts/controllers/**/*.js': ['coverage'],
      'src/scripts/directives/**/*.js': ['coverage'],
      'src/scripts/services/**/*.js': ['coverage'],
      'src/scripts/filters/**/*.js': ['coverage'],
      'src/scripts/inputs/**/*.js': ['coverage'],
      'src/scripts/app.js': ['coverage']
    },

    junitReporter: {
      outputDir: 'target/surefire-reports/',
      outputFile: undefined,
      suite: ''
    },

    ngJson2JsPreprocessor: {
      // strip this from the file path
      stripPrefix: 'test/mock/',
      // prepend this to the
      prependPrefix: 'served/'
    },

    coverageReporter: {
      type: "lcovonly",
      dir: 'target/coverage',
      file: '../../lcovUT.info'
    },
    singleRun: true,
    logLevel: config.LOG_INFO
  });
};
