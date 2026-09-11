type ApiEndpointProps = {
  method: 'GET' | 'POST';
  path: string;
};

export function ApiEndpoint({ method, path }: ApiEndpointProps) {
  return (
    <div className="api-endpoint" aria-label={`${method} ${path}`}>
      <span>{method}</span>
      <code>{path}</code>
    </div>
  );
}
