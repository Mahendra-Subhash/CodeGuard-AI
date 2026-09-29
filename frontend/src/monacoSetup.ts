/*
 * ============================================================
 * LOCAL, OFFLINE-FIRST MONACO SETUP
 * ============================================================
 *
 * Monaco is bundled from the locally installed `monaco-editor` package and its
 * workers are emitted as local Vite assets, so the editor never downloads code
 * from a public CDN. Previously @monaco-editor/react loaded monaco from
 * jsDelivr at runtime, which conflicts with the offline/privacy-first design.
 *
 * `loader.config({ monaco })` hands the already bundled instance to
 * @monaco-editor/react, which therefore never injects the remote AMD loader.
 * This module must be imported before any editor is rendered (see main.tsx).
 * ============================================================
 */
import * as monaco from 'monaco-editor';
import { loader } from '@monaco-editor/react';

/*
 * Workers are imported with Vite's `?worker` suffix so each one is emitted as a
 * local asset and instantiated lazily by Monaco, exactly like the CDN build did.
 */
import editorWorker from 'monaco-editor/editor/editor.worker.js?worker';
import jsonWorker from 'monaco-editor/language/json/json.worker.js?worker';
import cssWorker from 'monaco-editor/language/css/css.worker.js?worker';
import htmlWorker from 'monaco-editor/language/html/html.worker.js?worker';
import tsWorker from 'monaco-editor/language/typescript/ts.worker.js?worker';

interface MonacoWorkerEnvironment {
  getWorker(workerId: string, label: string): Worker;
}

type GlobalWithMonacoEnvironment = typeof globalThis & {
  MonacoEnvironment?: MonacoWorkerEnvironment;
};

const globalScope = globalThis as GlobalWithMonacoEnvironment;

globalScope.MonacoEnvironment = {
  getWorker(_workerId: string, label: string): Worker {
    if (label === 'json') {
      return new jsonWorker();
    }

    if (label === 'css' || label === 'scss' || label === 'less') {
      return new cssWorker();
    }

    if (label === 'html' || label === 'handlebars' || label === 'razor') {
      return new htmlWorker();
    }

    if (label === 'typescript' || label === 'javascript') {
      return new tsWorker();
    }

    return new editorWorker();
  },
};

loader.config({ monaco });

/**
 * Marker confirming that the locally bundled Monaco runtime is active.
 */
export const monacoRuntime = 'local-bundle';
