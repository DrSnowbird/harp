set terminal postscript eps enhanced monochrome dashed "Times-Roman" 25
set size 1,1
set xtics ("u3-1" 1, "u5-1" 2, "u5-2" 3, "u5-3" 4, "u7-1" 5)
set xlabel "templates"
set ylabel "running time (sec)"
set xrange[0.5:5.5]
set yrange[30:100000]
set log y
set key top right

plot 'harp-varying-templates-4-nodes-40-threads.data' using ($1-0.3):($2):(0.2) title "Web-Google" w boxes fs pattern, \
     'harp-varying-templates-4-nodes-40-threads.data' using ($1-0.1):($3):(0.2) title "Miami" w boxes fs pattern, \
     'harp-varying-templates-4-nodes-40-threads.data' using ($1+0.1):($4):(0.2) title "NYC" w boxes fs pattern, \
     'harp-varying-templates-4-nodes-40-threads.data' using ($1+0.3):($5):(0.2) title "Twitter" w boxes fs pattern


set output 'harp-varying-templates-4-nodes-40-threads.eps'
replot

