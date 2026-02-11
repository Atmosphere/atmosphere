import { optimize } from "svgo";
import { parse, renderSync } from "ultrahtml";
import { AstroError, AstroErrorData } from "../../core/errors/index.js";
import { dropAttributes } from "../runtime.js";
function parseSvg({
  path,
  contents,
  svgoConfig
}) {
  let processedContents = contents;
  if (svgoConfig) {
    try {
      const config = typeof svgoConfig === "boolean" ? void 0 : svgoConfig;
      const result = optimize(contents, config);
      processedContents = result.data;
    } catch (cause) {
      throw new AstroError(
        {
          ...AstroErrorData.CannotOptimizeSvg,
          message: AstroErrorData.CannotOptimizeSvg.message(path)
        },
        { cause }
      );
    }
  }
  const root = parse(processedContents);
  const svgNode = root.children.find(
    ({ name, type }) => type === 1 && name === "svg"
  );
  if (!svgNode) {
    throw new Error("SVG file does not contain an <svg> element");
  }
  const { attributes, children } = svgNode;
  const body = renderSync({ ...root, children });
  return { attributes, body };
}
function makeSvgComponent(meta, contents, svgoConfig) {
  const file = typeof contents === "string" ? contents : contents.toString("utf-8");
  const { attributes, body: children } = parseSvg({
    path: meta.fsPath,
    contents: file,
    svgoConfig
  });
  const props = {
    meta,
    attributes: dropAttributes(attributes),
    children
  };
  return `import { createSvgComponent } from 'astro/assets/runtime';
export default createSvgComponent(${JSON.stringify(props)})`;
}
export {
  makeSvgComponent
};
