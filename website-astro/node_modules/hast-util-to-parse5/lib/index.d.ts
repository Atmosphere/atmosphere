/**
 * Transform a hast tree to a `parse5` AST.
 *
 * @param {Nodes} tree
 *   Tree to transform.
 * @param {Options | null | undefined} [options]
 *   Configuration (optional).
 * @returns {Parse5Nodes}
 *   `parse5` node.
 */
export function toParse5(tree: Nodes, options?: Options | null | undefined): Parse5Nodes;
export type Parse5Document = DefaultTreeAdapterMap["document"];
export type Parse5Fragment = DefaultTreeAdapterMap["documentFragment"];
export type Parse5Element = DefaultTreeAdapterMap["element"];
export type Parse5Nodes = DefaultTreeAdapterMap["node"];
export type Parse5Doctype = DefaultTreeAdapterMap["documentType"];
export type Parse5Comment = DefaultTreeAdapterMap["commentNode"];
export type Parse5Text = DefaultTreeAdapterMap["textNode"];
export type Parse5Parent = DefaultTreeAdapterMap["parentNode"];
export type Parse5Attribute = Token.Attribute;
/**
 * Configuration.
 */
export type Options = {
    /**
     * Which space the document is in (default: `'html'`).
     *
     * When an `<svg>` element is found in the HTML space, this package already
     * automatically switches to and from the SVG space when entering and exiting
     * it.
     */
    space?: Space | null | undefined;
};
export type Parse5Content = Exclude<Parse5Nodes, Parse5Document | Parse5Fragment>;
export type Space = "html" | "svg";
import type { Nodes } from 'hast';
import type { DefaultTreeAdapterMap } from 'parse5';
import type { Token } from 'parse5';
//# sourceMappingURL=index.d.ts.map