import json, boto3, os

def handler(event, context):
    return {
        "statusCode": 200,
        "body": json.dumps({"message":"Lambda running with OCR container"})
    }
