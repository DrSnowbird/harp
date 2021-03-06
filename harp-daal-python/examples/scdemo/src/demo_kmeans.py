"""
===========================================================
A demo of K-Means clustering on dataset in the imagenet format
===========================================================

In this example we compare the various initialization strategies for
K-means in terms of runtime and quality of the results.

As the ground truth is known here, we also apply different cluster
quality metrics to judge the goodness of fit of the cluster labels to the
ground truth.

Cluster quality metrics evaluated (see :ref:`clustering_evaluation` for
definitions and discussions of the metrics):

=========== ========================================================
Shorthand    full name
=========== ========================================================
homo         homogeneity score
compl        completeness score
v-meas       V measure
ARI          adjusted Rand index
AMI          adjusted mutual information
silhouette   silhouette coefficient
=========== ========================================================

ref: http://scikit-learn.org/stable/auto_examples/cluster/plot_kmeans_digits.html#sphx-glr-auto-examples-cluster-plot-kmeans-digits-py

"""
from time import time
import numpy as np
import matplotlib
import sys
# Force matplotlib to not use any Xwindows backend.
matplotlib.use('Agg')
import matplotlib.pyplot as plt

from sklearn import metrics
from sklearn.cluster import KMeans
from sklearn.datasets import load_digits
from sklearn.decomposition import PCA
from sklearn.preprocessing import scale
import struct

from harp.daal.applications import KMeansDaalApplication

np.random.seed(42)

##############################################################################
#helper funcstions
def savedata(outfname, data):
    """
    save the data as harp-daal input format
    """
    ofile = open(outfname, "wb")
    for row in data:
        for cell in row:
            #ofile.write(struct.pack("=d", cell))
            ofile.write("%f\n"%cell)
    ofile.close()

class DAALKMeans():
    """
    interface to daal-kmeans call
    """
    def __init__(self, n_clusters=10, n_iter=10):
        self.labels_ = None
        self.centroids_ = None
        self.inertia_ = 0.
        self.n_iter = n_iter
        self.n_clusters = n_clusters

    def fit(self, data):
        #
        #call DAAL KMeans here, replace np.loadtxt by call_daal_kmeans
        #
        # self.centroids_ = np.loadtxt('output')
        #self.centroids_ = Call_DAAL_KMEANS(self.n_clusters, self.n_iter)a

        harp_kmeans = KMeansDaalApplication("Image clustering with Harp-Daal")
        harp_kmeans.set_workdir("/15scene-work")
        harp_kmeans.load_array("data/input.data", data)

        # random select centroids
        centroids = np.random.permutation(data.shape[0])[:self.n_clusters]
        centroids = data[centroids]

        harp_kmeans.init_centroids(centroids)
        #harp_kmeans.args(4485, self.n_clusters, 1000, 1, 1, 8, self.n_iter, 10240, harp_kmeans.get_workdir(), "/tmp/15scene", "false")
        harp_kmeans.args(data.shape[0], self.n_clusters, data.shape[1], 1, 1, 8, self.n_iter, 10240, harp_kmeans.get_workdir(), "/tmp/15scene", "false")
        harp_kmeans.run()
        self.centroids_ = harp_kmeans.result_to_array(harp_kmeans.get_workdir() + "/centroids/out/output")

        #self.centroids_ = self.centroids_.reshape((15,1000))
        #print('return centroids shape=%s'%(self.centroids_.shape))

        #patch here
        self.predict(data)

    def predict(self, data):
        """
        # hardcoded: minimize euclidean distance to cluster center:
        # ||a - b||^2 = ||a||^2 + ||b||^2 -2 <a, b>
        """
        a = data
        b = self.centroids_
        a2 = np.sum(a * a, axis =1)
        b2 = np.sum(b * b, axis =1)
        distmat = a2.reshape((a.shape[0],1)) - 2*np.dot(a, b.T)
        distmat = b2.T.reshape((1,b.shape[0])) + distmat

        self.labels_ = np.argmin(distmat, axis=1)


# #############################################################################
# loading the dataset

#set parameters
if len(sys.argv) == 1:
    print(__doc__)
    print('usage: demo_kmeans.py <dataset> <iternum> <daal> <runscale>')
else:
    resultfile = sys.argv[1] if len(sys.argv) > 1 else 'imagenet_10.npz'
    iternum = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    rundaal = True if len(sys.argv) > 3 and sys.argv[3]=='daal' else False
    runscale = True if len(sys.argv) <= 4 else False
    print('iternum: %d, result file: %s, runscale: %s'%(iternum, resultfile, runscale))

f = np.load(resultfile)
if runscale:
    data = scale(f['features'])
else:
    data = f['features']
labels_names = f['labels']
rids = f['rids']


#mapping labels_names to id
labelmap={}
labelid = 0
for label in labels_names:
    if not label in labelmap:
        labelmap[label] = labelid
        labelid += 1
labels = np.array([ labelmap[x] for x in labels_names])
np.savetxt('standard.cluster', labels)
np.savetxt('standard.rids', rids, fmt='%s')

n_samples, n_features = data.shape
n_digits = len(np.unique(labels))

print('cluster number: %d, labelid:%d'%(n_digits, labelid))

# #############################################################################
# run benchmark
sample_size = 300
print("===>run %s KMeans on dataset(n_cluster:%d,n_samples:%d,n_features:%d)"
      % ('HarpDAAL' if rundaal else 'Local', n_digits, n_samples, n_features))
print(82 * '_')
print('init\t\ttime\tinertia\thomo\tcompl\tv-meas\tARI\tAMI\tsilhouette')

def bench_k_means(estimator, name, data):
    t0 = time()
    estimator.fit(data)
    np.savetxt(name + '.cluster', estimator.labels_)
    print('%-9s\t%.2fs\t%i\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f'
          % (name, (time() - t0), estimator.inertia_,
             metrics.homogeneity_score(labels, estimator.labels_),
             metrics.completeness_score(labels, estimator.labels_),
             metrics.v_measure_score(labels, estimator.labels_),
             metrics.adjusted_rand_score(labels, estimator.labels_),
             metrics.adjusted_mutual_info_score(labels,  estimator.labels_),
             metrics.silhouette_score(data, estimator.labels_,
                                      metric='euclidean',
                                      sample_size=sample_size)))

#bench_k_means(KMeans(init='k-means++', n_clusters=n_digits, n_init=10),
#              name="k-means++", data=data)
#
#bench_k_means(KMeans(init='random', n_clusters=n_digits, n_init=10),
#              name="random", data=data)
#
## in this case the seeding of the centers is deterministic, hence we run the
## kmeans algorithm only once with n_init=1
#pca = PCA(n_components=n_digits).fit(data)
#bench_k_means(KMeans(init=pca.components_, n_clusters=n_digits, n_init=1),
#              name="PCA-based",
#              data=data)

#
#TODO: change the KMeans() call into harp_kmeans call
#
#savedata('input.data', data)
if rundaal:
    bench_k_means(DAALKMeans(n_clusters=n_digits, n_iter=50), name="random", data=data)
else:
    bench_k_means(KMeans(init='random', n_clusters=n_digits, n_init=50), name="random", data=data)

# #############################################################################
print(82 * '_')
# Visualize the results on PCA-reduced data
t1 = time()
reduced_data = PCA(n_components=2).fit_transform(data)
print('===>run PCA Dimension Reduction to 2D with %.2fs'%(time() - t1))

# kmenas on 2d dimension
t1 = time()
kmeans = KMeans(init='k-means++', n_clusters=n_digits, n_init=10)
kmeans.fit(reduced_data)
print('===>run KMeans on 2D dimension with %.2fs'%(time() - t1))

# Step size of the mesh. Decrease to increase the quality of the VQ.
t1 = time()
h = .02     # point in the mesh [x_min, x_max]x[y_min, y_max].

# Plot the decision boundary. For that, we will assign a color to each
x_min, x_max = reduced_data[:, 0].min() - 1, reduced_data[:, 0].max() + 1
y_min, y_max = reduced_data[:, 1].min() - 1, reduced_data[:, 1].max() + 1
xx, yy = np.meshgrid(np.arange(x_min, x_max, h), np.arange(y_min, y_max, h))

# Obtain labels for each point in mesh. Use last trained model.
Z = kmeans.predict(np.c_[xx.ravel(), yy.ravel()])
print('===>predict by trained KMeans model on 2D dimension with %.2fs'%(time() - t1))

# Put the result into a color plot
t1 = time()
Z = Z.reshape(xx.shape)
plt.figure(1)
plt.clf()
plt.imshow(Z, interpolation='nearest',
           extent=(xx.min(), xx.max(), yy.min(), yy.max()),
           cmap=plt.cm.Paired,
           aspect='auto', origin='lower')

plt.plot(reduced_data[:, 0], reduced_data[:, 1], 'k.', markersize=2)
# Plot the centroids as a white X
centroids = kmeans.cluster_centers_
plt.scatter(centroids[:, 0], centroids[:, 1],
            marker='x', s=169, linewidths=3,
            color='w', zorder=10)
plt.title('K-means clustering on the image dataset (PCA-reduced data)\n'
          'Centroids are marked with white cross')
plt.xlim(x_min, x_max)
plt.ylim(y_min, y_max)
plt.xticks(())
plt.yticks(())
plt.savefig('kmeans_2d.png')
plt.show()

print('===>draw plot of clusters on 2D dimension with %.2fs, check result at kmeans_2d.png'%(time() - t1))




