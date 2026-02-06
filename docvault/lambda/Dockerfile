FROM public.ecr.aws/lambda/python:3.11

RUN yum -y update &&     yum -y install tesseract tesseract-langpack-eng &&     yum clean all

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py ${LAMBDA_TASK_ROOT}

CMD ["app.handler"]
