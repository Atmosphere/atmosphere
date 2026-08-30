// A `ts` object for @vue/language-core assembled from TypeScript 7's public surface.
const parser = require('./ts7parser.cjs');

async function makeShim() {
  const ast = await import('typescript/unstable/ast');
  const SK = ast.SyntaxKind;
  const ts = Object.create(null);
  for (const [k, v] of Object.entries(ast)) ts[k] = v;

  // --- APIs TS 7 removed from JS, rebuilt on TS 7's own native compiler ---
  ts.createSourceFile = parser.createSourceFile;
  ts.forEachChild = (node, cb, cbArray) => node.forEachChild(cb, cbArray);

  // --- predicates TS 7 does not export, reconstructed from SyntaxKind ---
  const FUNCTION_LIKE = new Set([
    SK.FunctionDeclaration, SK.MethodDeclaration, SK.GetAccessor, SK.SetAccessor,
    SK.Constructor, SK.FunctionExpression, SK.ArrowFunction, SK.MethodSignature,
    SK.CallSignature, SK.ConstructSignature, SK.IndexSignature,
    SK.FunctionType, SK.ConstructorType, SK.JSDocFunctionType,
  ]);
  ts.isFunctionLike = n => !!n && FUNCTION_LIKE.has(n.kind);
  ts.isFunctionLikeDeclaration = ast.isFunctionLikeDeclaration
    ?? (n => !!n && FUNCTION_LIKE.has(n.kind));
  ts.isTypeReferenceNode = ast.isTypeReferenceNode;
  ts.isPropertySignature = ast.isPropertySignatureDeclaration;
  ts.isMethodSignature = ast.isMethodSignatureDeclaration;
  ts.isTypeElement = n => !!n && (n.kind === SK.PropertySignature || n.kind === SK.MethodSignature
    || n.kind === SK.CallSignature || n.kind === SK.ConstructSignature || n.kind === SK.IndexSignature);
  ts.getCombinedModifierFlags = n => n?.modifierFlagsCache ?? 0;

  return ts;
}
module.exports = { makeShim };
