if [ $# != 5 ] ; then
echo "USAGE: $0 <template> <input dir>  <output dir> <numMappers> <num threads per node>"
echo " e.g.: $0  template/u5-1.t graph out-sgcr 2 10"
exit 1;
fi
hdfs dfs -rm -r $3
hadoop jar $HARP_SAHAD_HOME/target/harp-sahad-1.0-SNAPSHOT.jar edu.iu.sahad.rotation.SCMapCollective $4 1 $1 $2 $3 $5