"""Pull the public Node base image through an explicit HTTP proxy, then docker load.

Uses Docker's v2 registry protocol and validates every compressed blob digest.
No Docker Desktop proxy/global settings are changed and existing containers stay running.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'artifacts' / 'node-image'
OUT.mkdir(parents=True, exist_ok=True)
PROXY = os.environ.get('IMAGE_PROXY', 'http://127.0.0.1:7890')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({'http': PROXY, 'https': PROXY}))
token_url = 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/node:pull'
with opener.open(token_url, timeout=60) as response:
    token = json.load(response)['token']
headers = {'Authorization': 'Bearer ' + token, 'Accept': ','.join([
    'application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json',
    'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'])}

def get_json(ref):
    req = urllib.request.Request('https://registry-1.docker.io/v2/library/node/manifests/' + ref, headers=headers)
    with opener.open(req, timeout=60) as response:
        return json.load(response)

index = get_json('24.12.0-bookworm-slim')
if 'manifests' in index:
    descriptor = next(m for m in index['manifests'] if m['platform']['os'] == 'linux' and m['platform']['architecture'] == 'amd64')
    manifest = get_json(descriptor['digest'])
else:
    manifest = index

def blob(digest):
    target = OUT / digest.split(':')[1]
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == digest.split(':')[1]:
        return target
    request = urllib.request.Request('https://registry-1.docker.io/v2/library/node/blobs/' + digest, headers={'Authorization': 'Bearer ' + token})
    # Registry redirects retain authentication only on registry origin; never forward bearer to a CDN.
    class Redirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, hdrs, newurl):
            next_request = super().redirect_request(req, fp, code, msg, hdrs, newurl)
            if next_request is not None:
                next_request.remove_header('Authorization')
            return next_request
    download = urllib.request.build_opener(urllib.request.ProxyHandler({'http': PROXY, 'https': PROXY}), Redirect())
    with download.open(request, timeout=120) as response, target.open('wb') as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest.split(':')[1]:
        raise RuntimeError('Blob SHA-256 mismatch')
    return target

config = blob(manifest['config']['digest'])
layers = []
for number, descriptor in enumerate(manifest['layers']):
    print('Proxy download layer', number + 1, 'of', len(manifest['layers']), flush=True)
    compressed = blob(descriptor['digest'])
    target = OUT / (str(number) + '-layer.tar')
    with gzip.open(compressed, 'rb') as source, target.open('wb') as output:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
    layers.append(target)
expected = json.loads(config.read_text())['rootfs']['diff_ids']
for layer, digest in zip(layers, expected):
    if hashlib.sha256(layer.read_bytes()).hexdigest() != digest.split(':')[1]:
        raise RuntimeError('Uncompressed layer SHA-256 mismatch')
archive_manifest = [{'Config': 'config.json', 'RepoTags': ['node:24.12.0-bookworm-slim'], 'Layers': [p.name for p in layers]}]
(OUT / 'manifest.json').write_text(json.dumps(archive_manifest))
archive = OUT / 'node-base.tar'
with tarfile.open(archive, 'w') as tar:
    tar.add(config, arcname='config.json')
    tar.add(OUT / 'manifest.json', arcname='manifest.json')
    for layer in layers:
        tar.add(layer, arcname=layer.name)
subprocess.run(['docker', 'load', '-i', str(archive)], check=True)
print('Node image imported; all remote registry/blob requests used', PROXY)
