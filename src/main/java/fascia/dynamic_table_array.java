package fascia;


public class dynamic_table_array extends dynamic_table{
    private float[][][] table;
    private float[][] cur_table;
    private float[][] cur_table_active;
    private float[][] cur_table_passive;

    int cur_sub;

    @Override
    public void init(Graph[] subtemplates, int num_subtemplates, int num_vertices, int num_colors) {
        this.subtemplates = subtemplates;
        this.num_subs = num_subtemplates;
        this.num_verts = num_vertices;
        this.num_colors = num_colors;
        init_choose_table();
        init_num_colorsets();

        table = new float[ this.num_subs ][][];

        assert(table != null);

        is_sub_inited = new boolean[ this.num_subs];

        assert (is_sub_inited != null);

        for(int s = 0; s < this.num_subs; ++s){
            is_sub_inited[s] = false;
        }

        is_inited = false;

    }

    @Override
    public void init_sub(int subtemplate) {
        this.table[subtemplate] = new float[ this.num_verts][];
        assert (table[subtemplate] != null);

        cur_table = table[subtemplate];
        cur_sub = subtemplate;

        /*
        #ifdef _OPENMP
        #pragma omp parallel for
        #endif
        for(int v = 0; v < num_verts; ++v){
            cur_table[v] = null;
        }
        */

        is_sub_inited[subtemplate] = true;

    }

    public void init_sub(int subtemplate, int active_child, int passive_child){
        if( active_child != Constants.NULL_VAL && passive_child != Constants.NULL_VAL){
            cur_table_active = table[active_child];
            cur_table_passive = table[passive_child];
        }else{
            cur_table_active = null;
            cur_table_passive = null;
        }

        if(subtemplate != 0){
            init_sub(subtemplate);
        }
    }

    @Override
    public void clear_sub(int subtemplate) {
        for(int v = 0; v < num_verts; ++v){
            if( table[subtemplate][v] != null){
                table[subtemplate][v] = null;
            }
        }

        if( is_sub_inited[subtemplate]){
            table[subtemplate] = null;
        }

        is_sub_inited[subtemplate] = false;
    }

    @Override
    public void clear_table() {
        for( int s = 0; s < num_subs; s++){
            if( is_sub_inited[s]){
                for(int v = 0; v < num_verts; ++v){
                    if ( table[s][v] != null){
                        table[s][v] = null;
                    }
                }
                table[s] = null;
                is_sub_inited[s] = false;
            }
        }

        table = null;
        is_sub_inited = null;
    }


    public float get(int subtemplate, int vertex, int comb_num_index){
        if( table[subtemplate][vertex] != null){
            float retval = table[subtemplate][vertex][comb_num_index];
            return retval;
        }else{
            return 0.0f;
        }
    }

    public float get_active(int vertex, int comb_num_index){
        if( cur_table_active[vertex] != null){
            return cur_table_active[vertex][comb_num_index];
        }else{
            return 0.0f;
        }
    }

    public float[] get_active(int vertex){
        return cur_table_active[vertex];
    }

    public float get_passive(int vertex, int comb_num_index){
        if( cur_table_passive[vertex] != null){
            return cur_table_passive[vertex][comb_num_index];
        }else{
            return 0.0f;
        }
    }

    //float* get(int subtemplate, int vertex) return table[subtemplate][vertex];
    //float* get_active(int vertex) return cur_table_active[vertex];
    //float* get_passive(int vertex) return cur_table_passive[vertex];

    public void set(int subtemplate, int vertex, int comb_num_index, float count){
        if( table[subtemplate][vertex] != null){

            table[subtemplate][vertex] = new float[ num_colorsets[subtemplate] ];
            assert(cur_table[vertex] != null);

            for(int c = 0; c < num_colorsets[subtemplate]; ++c){
                table[subtemplate][vertex][c] = 0.0f;
            }
        }

        table[subtemplate][vertex][comb_num_index]  = count;
    }


    public void set(int vertex, int comb_num_index, float count){
        if( cur_table[vertex] == null){
            cur_table[vertex] = new float[ num_colorsets[cur_sub] ];

            assert(cur_table[vertex] != null );

            for( int c = 0; c < num_colorsets[cur_sub]; ++c) {
                cur_table[vertex][c] = 0.0f;
            }
        }

        cur_table[vertex][comb_num_index] = count;
    }

    @Override
    public boolean is_init() {
        return this.is_inited;
    }

    @Override
    public boolean is_sub_init(int subtemplate) {
        return this.is_sub_inited[subtemplate];
    }

    public boolean is_vertex_init_active(int vertex){
        if( cur_table_active[vertex] != null)
            return true;
        else
            return false;
    }

    public boolean is_vertex_init_passive(int vertex){
        if(cur_table_passive[vertex] != null)
            return true;
        else
            return false;
    }
}
