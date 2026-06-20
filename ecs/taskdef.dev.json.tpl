{
  "family": "ems-dev",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::__ACCOUNT__:role/ems-dev-task-exec",
  "taskRoleArn": "arn:aws:iam::__ACCOUNT__:role/ems-dev-task",
  "containerDefinitions": [
    {
      "name": "ems",
      "image": "__IMAGE__",
      "essential": true,
      "portMappings": [
        { "containerPort": 8080, "protocol": "tcp" }
      ],
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE", "value": "dev" }
      ],
      "secrets": [
        { "name": "DB_HOST",     "valueFrom": "arn:aws:ssm:us-east-1:__ACCOUNT__:parameter/dev/ems/DB_HOST" },
        { "name": "DB_PORT",     "valueFrom": "arn:aws:ssm:us-east-1:__ACCOUNT__:parameter/dev/ems/DB_PORT" },
        { "name": "DB_NAME",     "valueFrom": "arn:aws:ssm:us-east-1:__ACCOUNT__:parameter/dev/ems/DB_NAME" },
        { "name": "DB_USERNAME", "valueFrom": "arn:aws:ssm:us-east-1:__ACCOUNT__:parameter/dev/ems/DB_USERNAME" },
        { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:us-east-1:__ACCOUNT__:secret:/dev/ems/DB_PASSWORD" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group":         "/ecs/ems-dev",
          "awslogs-region":        "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
