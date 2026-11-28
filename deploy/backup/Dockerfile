# 集群备份服务：用 MySQL 官方镜像自带的 mysqldump（与服务器版本一致），补上打包图片用的 tar / gzip
FROM mysql:8.0
RUN microdnf install -y tar gzip findutils && microdnf clean all
COPY backup.sh /usr/local/bin/storm-backup
RUN chmod +x /usr/local/bin/storm-backup
ENTRYPOINT ["/usr/local/bin/storm-backup"]
