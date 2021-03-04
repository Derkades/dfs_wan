# def app(environ, start_response):
#     data = b"Hello, World!\n"
#     start_response("200 OK", [
#         ("Content-Type", "text/plain"),
#         ("Content-Length", str(len(data)))
#     ])
#     return iter([data])

from flask import Flask, Response, request, abort, jsonify
from threading import Thread
from time import sleep
import schedule
import dsnapi
import threading
from os import environ as env
import os
from pathlib import Path
from requests.exceptions import RequestException

app = Flask(__name__)

def verify_request_auth(typ):
    if 'node_token' not in request.args:
        abort(401, 'Missing node_token')

    if typ == 'read':
        token = env['TOKEN'][:64]
    elif typ == 'write':
        token = env['TOKEN'][64:]
    elif typ == 'full':
        token = env['TOKEN']
    else:
        raise Exception('invalid type argument')

    if not request.args['node_token'] == token:
        abort(403, 'Invalid node_token')

def get_chunk_path(chunk_token, mkdirs=False):
    if len(chunk_token) != 128:
        print('get_chunk_path fail: chunk token length is ', len(chunk_token))
        return None
    if '/' in chunk_token or '\\' in chunk_token or '.' in chunk_token:
        print('get_chunk_path fail: contains invalid characters')
        return None
    base = env['DATA_DIR']
    dir_1 = chunk_token[:2]
    dir_2 = chunk_token[2:4]
    if mkdirs:
        Path(os.path.join(base, dir_1, dir_2)).mkdir(parents=True, exist_ok=True)
    file_name = chunk_token[4:]
    return os.path.join(base, dir_1, dir_2, file_name)


def read_chunk(chunk_token):
    """
    Read chunk data from local disk

    Parameters:
        chunk_token: Chunk token
    Returns:
        Chunk data, or None if the chunk does not exist locally
    """
    path = get_chunk_path(chunk_token)
    if not path:
        return None
    print('path', path)
    if not os.path.exists(path):
        return None
    with open(path, 'rb') as file:
        data = file.read()
    return data


def create_chunk(chunk_token, data):
    """
    Write chunk data to local disk, overwriting if the chunk already exists

    Parameters:
        chunk_token: Chunk token
        data: Chunk data
    Returns:
        success boolean
    """
    path = get_chunk_path(chunk_token, mkdirs=True)
    if not path:
        print('create_chunk fail, path is None')
        return False
    print('create_chunk: path exists:', os.path.exists(path))
    print('path', path)
    with open(path, 'wb') as file:
        file.write(data)
    return True

# def update_chunk(chunk_token, data):
#     """
#     Overwrite chunk data to local disk. Fails if the chunk does not exist.

#     Parameters:
#         chunk_token: Chunk token
#         data: Chunk data
#     Returns:
#         success boolean
#     """
#     path = get_chunk_path(chunk_token, mkdirs=True)
#     if not path:
#         print('update_chunk fail, path is None')
#         return False
#     if not os.path.exists(path):
#         print('update_chunk fail, chunk does not exist yet')
#         return False
#     print('path', path)
#     with open(path, 'wb') as file:
#         file.write(data)
#     return True


@app.route('/ping', methods=['GET'])
def ping():
    verify_request_auth('full')
    # if not request.json or 'number' not in request.json:
    #     abort(400)
    # num = request.json['number']

    # return jsonify({'answer': num ** 2})
    return Response(response='pong', content_type="text/plain")

@app.route('/upload', methods=['POST'])
def upload():
    verify_request_auth('write')

    if 'chunk_token' not in request.args:
        abort(400, 'Missing chunk_token')

    if request.content_type != 'application/octet-stream':
        abort(400, 'Request content type must be application/octet-stream')

    if request.data == b'':
        abort(400, 'Request body is empty')

    chunk_token = request.args['chunk_token']

    if not create_chunk(chunk_token, request.data):
        abort(500, 'Unable to write chunk data to file')

    (success, response, error_message) = dsnapi.notify_chunk_uploaded(chunk_token, len(request.data))
    if success:
        return Response('ok', content_type='text/plain')
    else:
        # TODO delete the chunk when this fails
        print('error sending chunk upload notification to master server:', response, error_message)
        abort(500, 'Unable to send chunk upload notification to master server')


# @app.route('/update', methods=['POST'])
# def update():
#     verify_request_auth('write')

#     if 'chunk_token' not in request.args:
#         abort(400, 'Missing chunk_token')

#     if request.content_type != 'application/octet-stream':
#         abort(400, 'Request content type must be application/octet-stream')

#     if request.data == b'':
#         abort(400, 'Request body is empty')

#     if update_chunk(request.args['chunk_token'], request.data):
#         return Response('ok', content_type='text/plain')
#     else:
#         abort(500, 'Unable to write chunk data to file')

@app.route('/download', methods=['GET'])
def download():
    verify_request_auth('read')

    if 'chunk_token' not in request.args:
        abort(400, 'Missing chunk_token')

    data = read_chunk(request.args['chunk_token'])
    if data:
        return Response(data, content_type='application/octet-stream')
    else:
        return abort(404, 'Chunk not found. Is the token valid and of the correct length?')

def announce():
    try:
        (success, response, error_message) = dsnapi.announce()
        if not success:
            print('Unable to contact master server:', response, error_message)
    except RequestException as e:
        print('Unable to contact master server:', e)

def timers():
    announce()
    schedule.every(5).to(10).seconds.do(announce)
    while True:
        schedule.run_pending()
        sleep(1)

t = threading.Thread(target=timers, args=[])
t.daemon = True # required to exit nicely on SIGINT
t.start()

# if __name__ == '__main__':
#     print('test', flush=True)
#     print('test')
#     thread = Thread(target = timers, args = (10, ))
#     thread.start()
#     thread.join()

#     app.run()

# if __name__ == '__main__':
#     app.run(host='127.0.0.1', port=8080, debug=True)
