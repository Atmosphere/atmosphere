import { promises } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { gzip, constants } from 'node:zlib';
import { stringify } from 'flatted';
import { resolve, basename, dirname, relative } from 'pathe';
import { globSync } from 'tinyglobby';
import c from 'tinyrainbow';

async function getModuleGraph(ctx, projectName, id, browser = false) {
  const graph = {};
  const externalized = /* @__PURE__ */ new Set();
  const inlined = /* @__PURE__ */ new Set();
  const project = ctx.getProjectByName(projectName);
  async function get(mod, seen = /* @__PURE__ */ new Map()) {
    if (!mod || !mod.id) {
      return;
    }
    if (mod.id === "\0@vitest/browser/context") {
      return;
    }
    if (seen.has(mod)) {
      return seen.get(mod);
    }
    let id2 = clearId(mod.id);
    seen.set(mod, id2);
    const rewrote = browser ? mod.file?.includes(project.browser.vite.config.cacheDir) ? mod.id : false : await project.vitenode.shouldExternalize(id2);
    if (rewrote) {
      id2 = rewrote;
      externalized.add(id2);
      seen.set(mod, id2);
    } else {
      inlined.add(id2);
    }
    const mods = Array.from(mod.importedModules).filter(
      (i) => i.id && !i.id.includes("/vitest/dist/")
    );
    graph[id2] = (await Promise.all(mods.map((m) => get(m, seen)))).filter(
      Boolean
    );
    return id2;
  }
  if (browser && project.browser) {
    await get(project.browser.vite.moduleGraph.getModuleById(id));
  } else {
    await get(project.server.moduleGraph.getModuleById(id));
  }
  return {
    graph,
    externalized: Array.from(externalized),
    inlined: Array.from(inlined)
  };
}
function clearId(id) {
  return id?.replace(/\?v=\w+$/, "") || "";
}

function getOutputFile(config) {
  if (!config?.outputFile) {
    return;
  }
  if (typeof config.outputFile === "string") {
    return config.outputFile;
  }
  return config.outputFile.html;
}
const distDir = resolve(fileURLToPath(import.meta.url), "../../dist");
class HTMLReporter {
  start = 0;
  ctx;
  reportUIPath;
  options;
  constructor(options) {
    this.options = options;
  }
  async onInit(ctx) {
    this.ctx = ctx;
    this.start = Date.now();
  }
  async onFinished() {
    const result = {
      paths: this.ctx.state.getPaths(),
      files: this.ctx.state.getFiles(),
      config: this.ctx.getCoreWorkspaceProject().getSerializableConfig(),
      unhandledErrors: this.ctx.state.getUnhandledErrors(),
      moduleGraph: {},
      sources: {}
    };
    await Promise.all(
      result.files.map(async (file) => {
        const projectName = file.projectName || "";
        result.moduleGraph[projectName] ??= {};
        result.moduleGraph[projectName][file.filepath] = await getModuleGraph(
          this.ctx,
          projectName,
          file.filepath
        );
        if (!result.sources[file.filepath]) {
          try {
            result.sources[file.filepath] = await promises.readFile(file.filepath, {
              encoding: "utf-8"
            });
          } catch {
          }
        }
      })
    );
    await this.writeReport(stringify(result));
  }
  async writeReport(report) {
    const htmlFile = this.options.outputFile || getOutputFile(this.ctx.config) || "html/index.html";
    const htmlFileName = basename(htmlFile);
    const htmlDir = resolve(this.ctx.config.root, dirname(htmlFile));
    const metaFile = resolve(htmlDir, "html.meta.json.gz");
    await promises.mkdir(resolve(htmlDir, "assets"), { recursive: true });
    const promiseGzip = promisify(gzip);
    const data = await promiseGzip(report, {
      level: constants.Z_BEST_COMPRESSION
    });
    await promises.writeFile(metaFile, data, "base64");
    const ui = resolve(distDir, "client");
    const files = globSync(["**/*"], { cwd: ui, expandDirectories: false });
    await Promise.all(
      files.map(async (f) => {
        if (f === "index.html") {
          const html = await promises.readFile(resolve(ui, f), "utf-8");
          const filePath = relative(htmlDir, metaFile);
          await promises.writeFile(
            resolve(htmlDir, htmlFileName),
            html.replace(
              "<!-- !LOAD_METADATA! -->",
              `<script>window.METADATA_PATH="${filePath}"<\/script>`
            )
          );
        } else {
          await promises.copyFile(resolve(ui, f), resolve(htmlDir, f));
        }
      })
    );
    this.ctx.logger.log(
      `${c.bold(c.inverse(c.magenta(" HTML ")))} ${c.magenta(
        "Report is generated"
      )}`
    );
    this.ctx.logger.log(
      `${c.dim("       You can run ")}${c.bold(
        `npx vite preview --outDir ${relative(this.ctx.config.root, htmlDir)}`
      )}${c.dim(" to see the test results.")}`
    );
  }
}

export { HTMLReporter as default };
